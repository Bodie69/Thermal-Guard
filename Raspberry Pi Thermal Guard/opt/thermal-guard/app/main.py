from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi import Request, Body, HTTPException
from fastapi.responses import JSONResponse, RedirectResponse
from datetime import datetime, timezone
from pathlib import Path
import threading, time, queue, os, socket, uuid, subprocess, traceback
import psutil
from . import config
from . import offline_store
from .local_access import is_local_client
from .pending_store import DurablePending
from . import supervisor
from .alarm import ZoneAlarm, CRITICAL_TEMP, WARNING_TEMP, RECOVERY
from .sensors import SensorManager
from .relay import RelayController
from .firebase_io import FirebaseIO
from .wifi_api import router as wifi_router

VERSION = '9.7.4'
# The backend is reachable from the LAN, so the auto-generated API docs
# (/docs, /redoc, /openapi.json), which list every endpoint, are turned off.
app = FastAPI(title='Thermal Guard', version=VERSION,
              docs_url=None, redoc_url=None, openapi_url=None)
app.include_router(wifi_router)
BASE = Path('/opt/thermal-guard')
STATIC_DIR = BASE / 'app' / 'static'
TEMPLATE_DIR = BASE / 'app' / 'templates'

app.mount('/static', StaticFiles(directory=str(STATIC_DIR)), name='static')
templates = Jinja2Templates(directory=str(TEMPLATE_DIR))
BOOT_ID = str(uuid.uuid4())
HOSTNAME = socket.gethostname()

def network_snapshot():
    """Return live LAN and Tailscale IPv4 addresses without shelling out.

    The app publishes these in each controller heartbeat so Android Settings can
    show the address that is actually valid on the Raspberry Pi right now.
    """
    local_ip = ''
    local_interface = ''
    tailscale_ip = ''
    candidates = []
    try:
        for ifname, addresses in psutil.net_if_addrs().items():
            lname = ifname.lower()
            for address in addresses:
                if address.family != socket.AF_INET:
                    continue
                ip = (address.address or '').strip()
                if not ip or ip.startswith('127.') or ip == '0.0.0.0':
                    continue
                if lname.startswith('tailscale'):
                    tailscale_ip = ip
                    continue
                if lname.startswith(('docker', 'veth', 'br-', 'virbr', 'zt')):
                    continue
                priority = 0
                if lname.startswith(('wlan', 'wl')):
                    priority = 30
                elif lname.startswith(('eth', 'en')):
                    priority = 20
                else:
                    priority = 10
                candidates.append((priority, ifname, ip))
    except Exception as exc:
        print(f'WARNING: network address detection failed: {exc}', flush=True)
    if candidates:
        _, local_interface, local_ip = sorted(candidates, key=lambda x: x[0], reverse=True)[0]
    port = int(config.PORT)
    return {
        'local_ip': local_ip,
        'local_interface': local_interface,
        'tailscale_ip': tailscale_ip,
        'web_port': port,
        'local_url': f'http://{local_ip}:{port}' if local_ip else '',
        'tailscale_url': f'http://{tailscale_ip}:{port}' if tailscale_ip else '',
    }

def now_iso():
    return datetime.now(timezone.utc).isoformat()

def controller_snapshot(status='ONLINE'):
    network = network_snapshot()
    return {
        'status': str(status).upper(),
        'last_seen': now_iso(),
        'last_seen_epoch_ms': int(time.time() * 1000),
        'boot_id': BOOT_ID,
        'hostname': HOSTNAME,
        'version': VERSION,
        'pid': os.getpid(),
        'uptime_seconds': int(time.monotonic()),
        **network,
    }

sensors = SensorManager()
relay = RelayController()
firebase = FirebaseIO()
state_lock = threading.RLock()
settings_lock = threading.RLock()
event_queue = queue.Queue(maxsize=500)
# Wakes the sensor loop immediately after Raspberry Pi/Firebase connectivity recovers.
sensor_refresh_event = threading.Event()

runtime_settings = {
    'warning_temp_c': config.WARNING_TEMP_C,
    'critical_temp_c': config.CRITICAL_TEMP_C,
}


def apply_threshold_settings(settings):
    try:
        w = float(settings.get('warning_temp_c', config.WARNING_TEMP_C))
        c = float(settings.get('critical_temp_c', config.CRITICAL_TEMP_C))
        if 0 <= w < c <= 80:
            with settings_lock:
                runtime_settings['warning_temp_c'] = w
                runtime_settings['critical_temp_c'] = c
    except Exception as exc:
        print('Settings parse error:', exc, flush=True)


# Start with the thresholds last saved from the app, so a reboot without
# internet does not fall back to the .env defaults.
apply_threshold_settings(offline_store.load_settings_cache())
# Alerts, system alerts and history that could not reach Firebase while
# offline. Saved on the SD card (data/firebase_outbox) so they survive a
# restart or power cut, and replayed in order when the connection returns.
offline_pending = DurablePending(offline_store.DATA_DIR / 'firebase_outbox',
                                 config.OFFLINE_PENDING_MAX)
FCM_REPLAY_MAX_AGE_SECONDS = 600
state = {
    'version': VERSION,
    'zones': {},
    'relay': relay.snapshot(),
    'sensor_mode': config.SENSOR_MODE,
    'warning_temp_c': config.WARNING_TEMP_C,
    'critical_temp_c': config.CRITICAL_TEMP_C,
    'last_update': None,
    'system_health': 'STARTING',
    'firebase_ready': firebase.ready,
    'firebase_error': firebase.last_error,
    'online_sensors': 0,
    'total_sensors': len(config.ZONES),
    'controller': controller_snapshot(),
}
alarms = {
    z.id: ZoneAlarm(
        critical_confirm=config.CRITICAL_CONFIRM_READS,
        recovery_confirm=config.RECOVERY_CONFIRM_READS,
        hysteresis=config.ALERT_HYSTERESIS_C,
        warning_realert_seconds=config.WARNING_REALERT_SECONDS,
    )
    for z in config.ZONES
}
offline_latched = {z.id: False for z in config.ZONES}
last_history = {z.id: 0.0 for z in config.ZONES}

# Sensor-loop liveness. The heartbeat thread checks this: if no sensor cycle
# has completed for SENSOR_STALL_SECONDS it raises a system alert, and after
# SENSOR_RESTART_SECONDS it exits the process so systemd (Restart=always)
# starts a fresh backend instead of reporting ONLINE with frozen readings.
SENSOR_STALL_SECONDS = 30
SENSOR_RESTART_SECONDS = 120
sensor_health = {'last_ok': time.monotonic(), 'errors': 0, 'consecutive_failures': 0,
                 'last_error': None, 'stalled': False}

# Raspberry Pi power supply state from `vcgencmd get_throttled`.
# Bit 0 = under-voltage now, bit 16 = under-voltage has occurred since boot,
# bit 2 / 18 = throttled now / since boot.
_POWER_CACHE_SECONDS = 15
_power_cache = {'at': 0.0, 'value': None}
_power_alerted = {'boot': False, 'now': False}


def power_snapshot():
    now = time.monotonic()
    if _power_cache['value'] is not None and now - _power_cache['at'] < _POWER_CACHE_SECONDS:
        return _power_cache['value']
    value = {'available': False}
    try:
        p = subprocess.run(['vcgencmd', 'get_throttled'], capture_output=True, text=True, timeout=5)
        raw = p.stdout.strip().split('=')[-1]
        bits = int(raw, 16)
        value = {
            'available': True, 'throttled_raw': raw,
            'undervoltage_now': bool(bits & 0x1), 'undervoltage_since_boot': bool(bits & 0x10000),
            'throttled_now': bool(bits & 0x4), 'throttled_since_boot': bool(bits & 0x40000),
        }
        value['ok'] = not (value['undervoltage_now'] or value['undervoltage_since_boot'])
    except Exception as exc:
        value = {'available': False, 'error': str(exc)}
    _power_cache.update(at=now, value=value)
    return value


def check_power_alerts(power):
    """One system alert per boot for past under-voltage, and one each time it starts happening now."""
    if not power.get('available'):
        return
    if power.get('undervoltage_since_boot') and not _power_alerted['boot']:
        _power_alerted['boot'] = True
        sysa = {'type': 'POWER_UNDERVOLTAGE', 'severity': 'WARNING', 'zone': 'system', 'name': 'Power supply',
                'message': 'Raspberry Pi power supply is too weak (under-voltage detected). '
                           'Use a 5.1 V / 2.5 A supply and power the SIM800L separately.',
                'timestamp': now_iso()}
        enqueue('system_alert', sysa)
        enqueue('fcm_system', sysa)
        print('[POWER] under-voltage has occurred since boot', flush=True)
    if power.get('undervoltage_now') and not _power_alerted['now']:
        _power_alerted['now'] = True
        print('[POWER] under-voltage right now', flush=True)
    elif not power.get('undervoltage_now'):
        _power_alerted['now'] = False


def system_snapshot():
    try:
        temp_path = Path('/sys/class/thermal/thermal_zone0/temp')
        cpu = round(int(temp_path.read_text().strip()) / 1000.0, 1) if temp_path.exists() else None
    except Exception:
        cpu = None
    disk = psutil.disk_usage('/')
    power = power_snapshot()
    check_power_alerts(power)
    return {
        'uptime_seconds': int(time.monotonic()),
        'cpu_temp_c': cpu,
        'disk_total_bytes': disk.total,
        'disk_free_bytes': disk.free,
        'disk_free_percent': round(disk.free / disk.total * 100, 1) if disk.total else 0,
        'load_1m': os.getloadavg()[0] if hasattr(os, 'getloadavg') else None,
        'power': power,
        'threads': threads_snapshot(),
        'relay_listener': relay_listener_snapshot(),
        'sensor_loop': {
            'errors': sensor_health['errors'],
            'consecutive_failures': sensor_health['consecutive_failures'],
            'last_error': sensor_health['last_error'],
        },
    }


def enqueue(kind, payload):
    if kind == 'alert':
        # The SMS path is local: the GSM worker sends this through the
        # SIM800L whether or not the internet is up.
        offline_store.queue_sms_alert(payload)
    try:
        event_queue.put_nowait((kind, payload))
    except queue.Full:
        print('WARNING: Firebase event queue full; dropping', kind, flush=True)


def sensor_loop():
    """Run sensor cycles forever. One failing cycle is logged and skipped;
    it can never stop monitoring for good (the heartbeat thread watches
    sensor_health and restarts the service if cycles stop completing)."""
    print('Thermal Guard sensor thread started', flush=True)
    while True:
        started = time.monotonic()
        try:
            _sensor_cycle(started)
            sensor_health['last_ok'] = time.monotonic()
            sensor_health['consecutive_failures'] = 0
        except Exception as exc:
            sensor_health['errors'] += 1
            sensor_health['consecutive_failures'] += 1
            sensor_health['last_error'] = f'{type(exc).__name__}: {exc}'
            print('[SENSOR] cycle failed; continuing:', sensor_health['last_error'], flush=True)
            traceback.print_exc()
        elapsed = time.monotonic() - started
        # Normal monitoring uses READ_INTERVAL_SECONDS, but a reconnect signal
        # wakes this wait immediately so fresh SHT31 values are published.
        wait_seconds = max(0.2, config.READ_INTERVAL_SECONDS - elapsed)
        sensor_refresh_event.wait(timeout=wait_seconds)
        sensor_refresh_event.clear()


_ALERT_TEXT = {
    CRITICAL_TEMP: ('CRITICAL', 'critical temperature'),
    WARNING_TEMP: ('WARNING', 'warning temperature'),
    RECOVERY: ('INFO', 'recovered'),
}


def _temperature_alert(kind, z, r, warning, critical):
    severity, text = _ALERT_TEXT[kind]
    return {
        'type': kind, 'severity': severity,
        'zone': z.id, 'name': z.name,
        'temperature': r.temperature, 'humidity': r.humidity,
        'warning_temp_c': warning, 'critical_temp_c': critical,
        'message': f'{z.name} {text}: {r.temperature:.1f} C',
        'timestamp': now_iso(), 'epoch': int(time.time()*1000),
    }


def _restore_siren_auto(new_critical):
    """End a manual siren OFF when it times out or a zone newly goes CRITICAL."""
    if new_critical and relay.rearm('new CRITICAL: ' + ', '.join(new_critical)):
        message = (f'Siren switched back to AUTO: {", ".join(new_critical)} reached CRITICAL '
                   f'temperature while the siren was OFF.')
        severity = 'WARNING'
    elif relay.expire_silence():
        minutes = config.SIREN_OFF_REVERT_MINUTES
        message = f'Siren was OFF for {minutes:.0f} min and is back to AUTO.'
        severity = 'INFO'
    else:
        return
    print('[SIREN]', message, flush=True)
    sysa = {'type': 'SIREN_AUTO_RESTORED', 'severity': severity, 'zone': 'system',
            'name': 'Siren', 'message': message, 'timestamp': now_iso()}
    enqueue('system_alert', sysa)
    enqueue('fcm_system', sysa)


def _sensor_cycle(started):
    """One read-evaluate-publish cycle over all zones."""
    with settings_lock:
        warning = float(runtime_settings['warning_temp_c'])
        critical = float(runtime_settings['critical_temp_c'])
    payload_zones = {}
    online_count = 0
    new_critical = []

    for z in config.ZONES:
        r = sensors.read(z)
        alarm = alarms[z.id]

        if r.temperature is not None:
            online_count += 1
            for kind in alarm.update(r.temperature, warning, critical, time.monotonic()):
                alert = _temperature_alert(kind, z, r, warning, critical)
                # App push + Alerts list always; SMS only when the alarm allows it
                # (WARNING SMS are limited to one per zone per 10 minutes).
                alert['sms'] = getattr(kind, 'sms', True)
                if kind == CRITICAL_TEMP:
                    new_critical.append(z.name)
                enqueue('alert', alert)
                enqueue('fcm_alert', alert)
                print(f'[ALARM] {z.id} {kind} at {r.temperature:.1f} C', flush=True)
            status = alarm.state
            if offline_latched[z.id]:
                offline_latched[z.id] = False
                sysa = {'type':'SENSOR_ONLINE','severity':'INFO','zone':z.id,'name':z.name,
                        'message':f'{z.name} sensor is online again','timestamp':now_iso()}
                enqueue('system_alert', sysa)
                enqueue('fcm_system', sysa)
        else:
            # No valid reading: hold the alarm (a CRITICAL zone keeps the siren
            # on) until real readings show recovery.
            alarm.missing_reading()
            status = 'OFFLINE'
            if r.consecutive_errors >= config.OFFLINE_CONFIRM_ERRORS and not offline_latched[z.id]:
                offline_latched[z.id] = True
                sysa = {'type':'SENSOR_OFFLINE','severity':'WARNING','zone':z.id,'name':z.name,
                        'message':f'{z.name} sensor offline: {r.error}','timestamp':now_iso()}
                enqueue('system_alert', sysa)
                enqueue('fcm_system', sysa)

        payload_zones[z.id] = {
            **r.to_dict(),
            'status': status,
            # Alarm level even while the sensor is OFFLINE (status shows OFFLINE).
            'alarm_state': alarm.state,
            'timestamp': now_iso(),
            'sample_epoch_ms': int((r.last_seen or time.time()) * 1000),
            'source': 'sht31_tca9548a',
        }
        if r.temperature is not None and time.time() - last_history[z.id] >= config.HISTORY_INTERVAL_SECONDS:
            last_history[z.id] = time.time()
            enqueue('history', {'zone_id': z.id, 'payload': {
                'temperature': r.temperature, 'humidity': r.humidity, 'status': status,
                'timestamp': now_iso(), 'epoch': int(time.time()*1000),
            }})

    any_critical = any(a.critical for a in alarms.values())
    _restore_siren_auto(new_critical)
    relay.set_auto_demand(any_critical)
    if online_count == 0 and config.RELAY_FAILSAFE_OFF_IF_ALL_SENSORS_OFFLINE:
        relay.force_off()
    relay_snapshot = relay.snapshot()
    health = 'HEALTHY' if online_count == len(config.ZONES) and relay_snapshot['available'] else 'ALL_SENSORS_OFFLINE' if online_count == 0 else 'DEGRADED'
    payload = {
        'version': VERSION, 'zones': payload_zones, 'relay': relay_snapshot,
        'sensor_mode': config.SENSOR_MODE,
        'warning_temp_c': warning, 'critical_temp_c': critical,
        'last_update': now_iso(), 'system_health': health,
        'online_sensors': online_count, 'total_sensors': len(config.ZONES),
        'firebase_ready': firebase.ready, 'firebase_error': firebase.last_error,
        'firebase_connected': firebase.connected,
        # Real reachability (what the kiosk shows); firebase_ready is only "SDK set up".
        'firebase_online': firebase.online,
        'firebase_last_ok_age_s': firebase.last_ok_age_seconds(),
        'offline_pending': len(offline_pending),
        'loop_ms': round((time.monotonic()-started)*1000,1),
        'sample_epoch_ms': int(time.time()*1000),
        'source': 'raspberry_pi_sht31',
        'controller': controller_snapshot(),
        'system': system_snapshot(),
    }
    with state_lock:
        state.update(payload)
    # Publish this exact sensor-cycle snapshot asynchronously. This avoids
    # an extra polling delay between Raspberry Pi and Firebase.
    enqueue('live', payload)


def controller_heartbeat_loop():
    """Independent controller heartbeat with automatic reconnect recovery.

    If Firebase/network connectivity is lost, retry quickly. The first successful
    heartbeat after an outage wakes the sensor loop immediately, causing fresh
    SHT31 readings to be published to Firebase while Android listeners stay open.
    """
    print('Thermal Guard controller heartbeat thread started', flush=True)
    firebase_connected = False
    while True:
        heartbeat_ok = False
        try:
            payload = controller_snapshot('ONLINE')
            with state_lock:
                state['controller'] = payload

            # Success of THIS call - firebase.last_error can be changed by other
            # threads' calls running at the same time.
            heartbeat_ok = firebase.set_controller_heartbeat(payload)

            if heartbeat_ok and not firebase_connected:
                firebase_connected = True
                sensor_refresh_event.set()
                print(
                    '[RECOVERY] Raspberry Pi/Firebase connection ONLINE -> '
                    'immediate sensor read requested',
                    flush=True,
                )
            elif not heartbeat_ok and firebase_connected:
                firebase_connected = False
                print(
                    '[RECOVERY] Firebase connection lost -> fast reconnect retry enabled',
                    flush=True,
                )
        except Exception as exc:
            print(f'[HEARTBEAT] cycle failed; continuing: {type(exc).__name__}: {exc}', flush=True)
            traceback.print_exc()

        # The sensor-stall watchdog runs even when the heartbeat itself failed.
        check_sensor_loop()

        # Normal operation keeps the configured heartbeat interval. During an
        # outage retry every second so ONLINE recovery is detected quickly.
        time.sleep(config.CONTROLLER_HEARTBEAT_SECONDS if heartbeat_ok else 1.0)


def check_sensor_loop():
    """Alert when sensor cycles stop completing; restart the service if it lasts."""
    age = time.monotonic() - sensor_health['last_ok']
    if age >= SENSOR_STALL_SECONDS and not sensor_health['stalled']:
        sensor_health['stalled'] = True
        detail = sensor_health['last_error'] or 'no sensor cycle completed'
        print(f'[SENSOR] monitoring stalled for {age:.0f}s: {detail}', flush=True)
        with state_lock:
            state['system_health'] = 'SENSOR_LOOP_STALLED'
        sysa = {'type': 'MONITORING_STALLED', 'severity': 'WARNING', 'zone': 'system', 'name': 'Controller',
                'message': f'Temperature monitoring stopped updating ({detail}). '
                           f'The controller will restart itself if it does not recover.',
                'timestamp': now_iso()}
        enqueue('system_alert', sysa)
        enqueue('fcm_system', sysa)
        # Push this alert directly too, in case the publisher thread is the one stuck.
        firebase.push_ok('/thermal_guard/system_alerts', sysa)
    elif age < SENSOR_STALL_SECONDS and sensor_health['stalled']:
        sensor_health['stalled'] = False
        print('[SENSOR] monitoring recovered', flush=True)
    if age >= SENSOR_RESTART_SECONDS:
        print(f'[SENSOR] no sensor cycle for {age:.0f}s -> exiting so systemd restarts the backend', flush=True)
        try:
            relay.force_off()
        finally:
            os._exit(3)


def _command_age_seconds(requested_at):
    try:
        raw = float(requested_at)
        epoch_seconds = raw / 1000.0 if raw > 10_000_000_000 else raw
        return max(0.0, time.time() - epoch_seconds)
    except Exception:
        return None


def firebase_settings_loop():
    print('Thermal Guard Firebase settings thread started', flush=True)
    while True:
        # Offline: keep the current thresholds instead of resetting to defaults.
        if firebase.connected and firebase.ensure_ready():
            settings = firebase.get_runtime_settings()
            if settings:
                offline_store.save_settings_cache(settings)
                apply_threshold_settings(settings)
        time.sleep(5)


# Kept outside the loop so a supervisor restart of the thread does not
# re-apply an app command that was already handled (e.g. over a newer kiosk one).
_relay_cmd = {'last_key': None}

# Siren commands from the app arrive through a Firebase streaming listener
# (one long-lived connection) that wakes this thread at once, instead of
# reading the command node 4 times a second (~345,000 requests/day). A slow
# check every RELAY_FALLBACK_POLL_SECONDS still runs in case the stream
# silently stalls (the SDK stream has no timeout), and a dead listener is
# restarted automatically.
RELAY_FALLBACK_POLL_SECONDS = 10.0
relay_wake = threading.Event()
_relay_listener = {'reg': None, 'events': 0, 'starts': 0, 'last_event_at': None}


def _on_relay_command_event(event):
    """Runs on the SDK's listener thread; must never raise (that kills the stream)."""
    try:
        path = str(getattr(event, 'path', '/') or '/')
        if path.startswith('/ack'):
            return          # our own acknowledgement write, not a new command
        _relay_listener['events'] += 1
        _relay_listener['last_event_at'] = now_iso()
        relay_wake.set()
    except Exception:
        pass


def _relay_listener_alive():
    reg = _relay_listener['reg']
    thread = getattr(reg, '_thread', None)
    return bool(thread is not None and thread.is_alive())


def _ensure_relay_listener():
    if _relay_listener_alive():
        return
    _stop_relay_listener()
    reg = firebase.listen_relay_command(_on_relay_command_event)
    if reg is not None:
        _relay_listener['reg'] = reg
        _relay_listener['starts'] += 1
        print(f"[SIREN] live command listener started (#{_relay_listener['starts']})", flush=True)


def _stop_relay_listener():
    reg, _relay_listener['reg'] = _relay_listener['reg'], None
    if reg is None:
        return
    # close() joins the stream thread and could block on a dead socket;
    # close the stream from a throwaway daemon thread instead.
    sse = getattr(reg, '_sse', None)
    if sse is not None:
        threading.Thread(target=sse.close, name='tg-relay-listener-close', daemon=True).start()


def relay_listener_snapshot():
    return {'alive': _relay_listener_alive(), 'events': _relay_listener['events'],
            'starts': _relay_listener['starts'], 'last_event_at': _relay_listener['last_event_at'],
            'fallback_poll_seconds': RELAY_FALLBACK_POLL_SECONDS}


def relay_command_loop():
    print('Thermal Guard siren/relay command thread started', flush=True)
    while True:
        last_processed_key = _relay_cmd['last_key']
        if not firebase.connected:
            # Offline: AUTO siren control keeps running locally in sensor_loop.
            # Drop the stream so a fresh one is opened after reconnecting.
            _stop_relay_listener()
            time.sleep(1.0)
            continue
        # Started from this daemon thread, so the stream thread is a daemon too.
        _ensure_relay_listener()
        relay_wake.clear()          # events after this point trigger another read
        cmd = firebase.get_relay_command() if firebase.ensure_ready() else None
        if cmd:
            mode = cmd.get('mode')
            request_id = str(cmd.get('requestId') or '')
            requested_by = str(cmd.get('requestedBy') or '')
            # Old database commands may not have requestId. requestedAt keeps them
            # deterministic while still allowing a newly written command to run.
            key = request_id or f"{mode}:{cmd.get('requestedAt')}"
            if key != last_processed_key:
                age = _command_age_seconds(cmd.get('requestedAt'))
                if age is not None and age > config.RELAY_COMMAND_MAX_AGE_SECONDS:
                    snap = relay.snapshot()
                    firebase.set_relay_live(snap)
                    firebase.set_relay_ack({
                        'requestId': request_id or key,
                        'mode': mode,
                        'applied': False,
                        'relayOn': bool(snap.get('on')),
                        'autoDemand': bool(snap.get('auto_demand')),
                        'available': bool(snap.get('available')),
                        'error': f'Expired command ignored after {age:.1f}s offline/restart delay',
                        'appliedAt': now_iso(),
                        'requestedBy': requested_by,
                    })
                    print(
                        f"[SIREN] ignored stale mode={mode.upper()} age={age:.1f}s "
                        f"request={request_id or key}",
                        flush=True,
                    )
                else:
                    applied = relay.set_mode(
                        mode,
                        request_id=request_id or key,
                        requested_by=requested_by,
                    )
                    snap = relay.snapshot()
                    with state_lock:
                        state['relay'] = snap
                        state['last_update'] = now_iso()

                    # Immediate state + acknowledgement removes the normal sensor-loop
                    # delay from manual ON/AUTO/OFF control.
                    firebase.set_relay_live(snap)
                    firebase.set_relay_ack({
                        'requestId': request_id or key,
                        'mode': mode,
                        'applied': bool(applied),
                        'relayOn': bool(snap.get('on')),
                        'autoDemand': bool(snap.get('auto_demand')),
                        'available': bool(snap.get('available')),
                        'error': snap.get('error'),
                        'appliedAt': now_iso(),
                        'requestedBy': requested_by,
                    })
                    print(
                        f"[SIREN] mode={mode.upper()} applied={applied} "
                        f"gpio_on={snap.get('on')} request={request_id or key}",
                        flush=True,
                    )
                _relay_cmd['last_key'] = key
        # Sleep until the listener reports a change (or the fallback check is due).
        relay_wake.wait(timeout=RELAY_FALLBACK_POLL_SECONDS)


def _deliver(kind, payload, queued_at):
    """Send one queued event to Firebase. Returns False if it should be kept."""
    if kind == 'alert':
        return firebase.push_ok('/thermal_guard/alerts', payload)
    if kind == 'system_alert':
        return firebase.push_ok('/thermal_guard/system_alerts', payload)
    if kind == 'history':
        return firebase.push_ok(f"/thermal_guard/history/{payload['zone_id']}", payload['payload'])
    if kind in ('fcm_alert', 'fcm_system'):
        # A phone notification hours late is confusing; the alert itself is
        # still replayed into the Alerts list.
        if time.time() - queued_at > FCM_REPLAY_MAX_AGE_SECONDS:
            return True
        if kind == 'fcm_alert':
            return firebase.send_topic('thermal_guard_alerts', 'Thermal Guard', payload.get('message', 'Temperature alert'), payload)
        return firebase.send_topic('thermal_guard_system', 'Thermal Guard System', payload.get('message', 'System alert'), payload)
    return True


def _flush_offline_pending(limit=50):
    sent = 0
    while offline_pending and sent < limit and firebase.connected:
        kind, payload, queued_at = offline_pending[0]
        if not _deliver(kind, payload, queued_at):
            return
        offline_pending.popleft()
        sent += 1
    if sent and not offline_pending:
        print('[OFFLINE] all events saved while offline were sent to Firebase', flush=True)


def firebase_worker_loop():
    print('Thermal Guard Firebase publisher thread started', flush=True)
    last_live_publish = 0.0
    while True:
        try:
            try:
                kind, payload = event_queue.get(timeout=1.0)
            except queue.Empty:
                kind, payload = None, None

            if kind == 'live':
                # Live data is only useful now; while offline it stays in
                # state for the kiosk and /api/live.
                if firebase.connected:
                    firebase.set_live(payload)
                    last_live_publish = time.time()
            elif kind is not None:
                queued_at = time.time()
                if offline_pending or not firebase.connected or not _deliver(kind, payload, queued_at):
                    if not offline_pending:
                        print('[OFFLINE] Firebase unreachable; keeping events locally', flush=True)
                    offline_pending.append((kind, payload, queued_at))

            if firebase.connected:
                _flush_offline_pending()
                # Safety refresh: if no live event went out for 10 seconds,
                # republish the most recent state.
                if time.time() - last_live_publish >= 10.0:
                    with state_lock:
                        snapshot = dict(state)
                    firebase.set_live(snapshot)
                    last_live_publish = time.time()
        except Exception as exc:
            print('Firebase publisher loop error:', exc, flush=True)
            time.sleep(2)

_threads = []
THREAD_ALERT_INTERVAL_SECONDS = 3600
_thread_alerted = {}


def _report_thread_crash(name, error, count):
    """System alert when a thread crashes - at most once per thread per hour."""
    now = time.monotonic()
    if now - _thread_alerted.get(name, -THREAD_ALERT_INTERVAL_SECONDS) < THREAD_ALERT_INTERVAL_SECONDS:
        return
    _thread_alerted[name] = now
    sysa = {'type': 'CONTROLLER_THREAD_RESTARTED', 'severity': 'WARNING', 'zone': 'system',
            'name': 'Controller',
            'message': f'Controller task {name} crashed and was restarted automatically '
                       f'(restart #{count}): {error[:200]}',
            'timestamp': now_iso()}
    enqueue('system_alert', sysa)
    enqueue('fcm_system', sysa)


def _start(name, target):
    # A crash restarts the loop instead of silently ending the thread.
    runner = supervisor.supervised(name, target, on_crash=_report_thread_crash)
    t = threading.Thread(target=runner, name=name, daemon=True)
    t.start(); _threads.append(t)


def threads_snapshot():
    health = supervisor.health_snapshot()
    return {t.name: {'alive': t.is_alive(), **health.get(t.name, {})} for t in _threads}

@app.on_event('startup')
async def startup():
    _start('tg-heartbeat', controller_heartbeat_loop)
    _start('tg-sensors', sensor_loop)
    _start('tg-settings', firebase_settings_loop)
    _start('tg-relay', relay_command_loop)
    _start('tg-firebase', firebase_worker_loop)

@app.on_event('shutdown')
async def shutdown():
    # Best-effort clean shutdown marker. Sudden power/network loss is detected
    # by the Android heartbeat timeout instead.
    _stop_relay_listener()
    firebase.set_controller_heartbeat(controller_snapshot('OFFLINE'))
    relay.force_off()

@app.get('/health')
def health():
    with state_lock:
        s = dict(state)
    return {'ok': True, 'version': VERSION, 'system_health': s.get('system_health'),
            'online_sensors': s.get('online_sensors',0), 'total_sensors': s.get('total_sensors',3),
            'firebase_ready': firebase.ready, 'firebase_error': firebase.last_error,
            'firebase_online': firebase.online}

@app.get('/ready')
def ready():
    with state_lock:
        s = dict(state)
    ok = firebase.ready and relay.snapshot()['available'] and s.get('online_sensors') == len(config.ZONES)
    return {'ready': ok, 'online_sensors': s.get('online_sensors',0), 'total_sensors': len(config.ZONES),
            'firebase_ready': firebase.ready, 'relay_available': relay.snapshot()['available']}

# Last time the kiosk page itself polled /api/live (it sends X-TG-Kiosk: 1).
# The kiosk watchdog script restarts Chromium when this goes stale, e.g. after
# an "Aw, Snap!" renderer crash or a page frozen by a dialog.
kiosk_poll = {'last': 0.0, 'count': 0}


@app.get('/api/live')
def live(request: Request):
    if request.headers.get('x-tg-kiosk') == '1' and is_local_client(request):
        kiosk_poll['last'] = time.time()
        kiosk_poll['count'] += 1
    with state_lock:
        return dict(state)


@app.get('/api/kiosk/status')
def kiosk_status():
    last = kiosk_poll['last']
    return {'last_poll_epoch': last or None,
            'seconds_since_poll': round(time.time() - last, 1) if last else None,
            'polls': kiosk_poll['count']}

@app.get('/api/sms')
def sms_status():
    """Local SMS status; works without internet."""
    sms = (offline_store.load_settings_cache().get('sms') or {})
    app_numbers = [n for n in (offline_store.normalize_number(v) for v in (sms.get('recipients') or {}).values()) if n]
    fallback = offline_store.fallback_recipients()
    # This endpoint is readable from the LAN, so every phone number in it
    # (GSM status, SMS log, modem error echoes) is masked: +63*******567.
    return offline_store.mask_phone_numbers({
        'gsm': offline_store.read_json(offline_store.GSM_STATUS, {}) or {},
        # Counts only; phone numbers are never returned here.
        'recipients': {
            'app_enabled': bool(sms.get('enabled')),
            'app_count': len(app_numbers) if sms.get('enabled') else 0,
            'fallback_count': len(fallback),
            'configured': bool((sms.get('enabled') and app_numbers) or fallback),
        },
        'outbox_pending': len(offline_store.outbox_items()),
        'recent': offline_store.recent_sms_log(20),
        'firebase_connected': firebase.connected,
        'offline_pending': len(offline_pending),
    })

@app.post('/api/siren/mode')
def siren_mode(request: Request, body: dict = Body(default={})):
    """Siren AUTO / ON / OFF from the dashboard: the kiosk touchscreen or any
    device on the network that opens it (no login; the owner chose to allow
    this). Each change records who made it: 'kiosk' or 'web <client ip>'."""
    mode = str((body or {}).get('mode', '')).lower().strip()
    if mode not in ('auto', 'on', 'off'):
        raise HTTPException(status_code=400, detail='Mode must be auto, on or off.')
    if is_local_client(request):
        source = 'kiosk'
    else:
        source = f'web {request.client.host if request.client else "unknown"}'
    request_id = f'{source.split()[0]}-{uuid.uuid4().hex[:12]}'
    applied = relay.set_mode(mode, request_id=request_id, requested_by=source)
    snap = relay.snapshot()
    with state_lock:
        state['relay'] = snap
        state['last_update'] = now_iso()
    print(f'[SIREN] mode={mode.upper()} applied={applied} gpio_on={snap.get("on")} request={request_id} ({source})', flush=True)

    def _publish():
        # Show the new mode in the app right away. Firebase commands/relay is left
        # untouched, so an older app command is not re-applied over this one.
        firebase.set_relay_live(snap)
        firebase.set_relay_ack({'requestId': request_id, 'mode': mode, 'applied': bool(applied),
                                'relayOn': bool(snap.get('on')), 'autoDemand': bool(snap.get('auto_demand')),
                                'available': bool(snap.get('available')), 'error': snap.get('error'),
                                'appliedAt': now_iso(), 'requestedBy': source})
    if firebase.connected:
        threading.Thread(target=_publish, name='tg-siren-publish', daemon=True).start()

    if not applied:
        raise HTTPException(status_code=500, detail=f'Siren relay did not respond: {snap.get("error") or "unavailable"}')
    return {'ok': True, 'mode': mode, 'on': bool(snap.get('on')), 'relay': snap}


@app.get('/kiosk')
def kiosk(request: Request):
    return templates.TemplateResponse(
        request=request,
        name='kiosk.html',
        context={}
    )

@app.get('/', include_in_schema=False)
def root():
    # Readable from the LAN: send people to the dashboard instead of
    # advertising the version and API endpoints.
    return RedirectResponse(url='/kiosk', status_code=307)
