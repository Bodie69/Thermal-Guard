#!/usr/bin/env python3
"""Thermal Guard SIM800L SMS worker.

Works with or without internet:
  * Alerts come from the local outbox (data/sms_outbox) written by the sensor
    service, not from Firebase.
  * Recipients come from the last SMS settings cached from the app, plus the
    always-on SMS_FALLBACK_RECIPIENTS in .env.
  * Firebase is used only when reachable: test SMS requests from the app,
    GSM status, and sms_logs (buffered while offline, uploaded later).
"""
import time
from collections import deque
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import firebase_admin
from firebase_admin import credentials, db

from app import config
from app import offline_store as store
from app.sim800l import SIM800L

STATE_FILE = store.DATA_DIR / 'gsm_state.json'
PH_TZ = ZoneInfo('Asia/Manila')
FIREBASE_RETRY_SECONDS = 15.0


def iso():
    return datetime.now(timezone.utc).isoformat()


def ph_time_text(value=None):
    try:
        if value:
            raw = str(value).strip()
            if raw.endswith('Z'):
                raw = raw[:-1] + '+00:00'
            dt = datetime.fromisoformat(raw)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
        else:
            dt = datetime.now(timezone.utc)
        return dt.astimezone(PH_TZ).strftime('%b %d, %Y %I:%M:%S %p PHT')
    except Exception:
        return datetime.now(PH_TZ).strftime('%b %d, %Y %I:%M:%S %p PHT')


class FirebaseLink:
    """Best-effort Firebase access that never blocks SMS sending for long."""

    def __init__(self):
        self.connected = False
        self.next_try = 0.0
        self.pending_logs = deque(maxlen=500)
        self.pending_status = {}

    def _init(self):
        if not firebase_admin._apps:
            firebase_admin.initialize_app(
                credentials.Certificate(config.FIREBASE_SERVICE_ACCOUNT),
                {'databaseURL': config.FIREBASE_DATABASE_URL,
                 'httpTimeout': config.FIREBASE_HTTP_TIMEOUT_SECONDS})

    def call(self, fn, default=None):
        """Run fn() against Firebase. While offline, only retry every 15 s."""
        if not self.connected and time.time() < self.next_try:
            return default
        try:
            self._init()
            out = fn()
            if not self.connected:
                print('[GSM] Firebase reachable', flush=True)
            self.connected = True
            return out
        except Exception as exc:
            if self.connected:
                print('[GSM] Firebase unreachable, continuing offline:', exc, flush=True)
            self.connected = False
            self.next_try = time.time() + FIREBASE_RETRY_SECONDS
            return default

    def update_status(self, fields):
        self.pending_status.update(fields)
        ok = self.call(lambda: db.reference('/thermal_guard/system/live/gsm').update(self.pending_status) or True, False)
        if ok:
            self.pending_status = {}

    def log(self, entry):
        self.pending_logs.append(entry)
        self.flush_logs()

    def flush_logs(self):
        while self.pending_logs:
            entry = self.pending_logs[0]
            if not self.call(lambda: db.reference('/thermal_guard/sms_logs').push(entry) and True, False):
                return
            self.pending_logs.popleft()


fb = FirebaseLink()


def load_state():
    return store.read_json(STATE_FILE, {}) or {}


def save_state(st):
    store.write_json(STATE_FILE, st)


def write_status(fields):
    """Keep GSM status on disk (for /api/sms) and in Firebase when online."""
    current = store.read_json(store.GSM_STATUS, {}) or {}
    current.update(fields)
    current['timestamp'] = iso()
    store.write_json(store.GSM_STATUS, current)
    fb.update_status(fields | {'timestamp': current['timestamp']})


def check_modem(modem):
    status = {'enabled': config.SIM800L_ENABLED, 'ready': False, 'registered': False,
              'signal': -1, 'operator': '', 'error': ''}
    try:
        status['ready'] = bool(modem.open())
        if status['ready']:
            reg, code, _ = modem.registration()
            status['registered'] = reg
            status['registration_code'] = code
            status['signal'], _ = modem.signal()
            status['operator'] = modem.operator()
        else:
            status['error'] = modem.last_error or 'SIM800L did not answer AT'
            modem.close()  # reopen and re-check AT next time
    except Exception as exc:
        status['error'] = str(exc)
        modem.close()
    status['outbox_pending'] = len(store.outbox_items())
    status['internet'] = fb.connected
    write_status(status)
    return status


def log_sms(number, typ, ok, detail, event_key=''):
    entry = {
        'recipient': number, 'type': typ, 'success': ok,
        'status': 'SENT' if ok else 'FAILED', 'detail': str(detail)[-500:],
        'eventKey': event_key, 'timestamp': iso(), 'epoch': int(time.time() * 1000),
    }
    store.append_sms_log(entry)
    fb.log(entry)
    write_status({'last_sms_status': entry['status'], 'last_sms_at': entry['timestamp'],
                  'last_sms_recipient': number, 'error': '' if ok else str(detail)[-300:]})


def type_allowed(sms, typ):
    return ((typ == 'WARNING_TEMP' and sms.get('send_warning', True)) or
            (typ == 'CRITICAL_TEMP' and sms.get('send_critical', True)) or
            (typ == 'RECOVERY' and sms.get('send_recovery', True)))


def recipients_for(typ):
    """App recipients (last cached settings) + .env fallback numbers."""
    out = []
    sms = store.load_settings_cache().get('sms') or {}
    if sms.get('enabled') and type_allowed(sms, typ):
        r = sms.get('recipients') or {}
        for key in ('primary', 'secondary', 'tertiary'):
            n = store.normalize_number(r.get(key))
            if n and n not in out:
                out.append(n)
    if typ in store.fallback_types():
        for n in store.fallback_recipients():
            if n not in out:
                out.append(n)
    return out


def alert_message(alert):
    typ = str(alert.get('type', ''))
    name = alert.get('name') or alert.get('zone') or 'Zone'
    t = alert.get('temperature')
    h = alert.get('humidity')
    t_text = f'{t:.1f}' if isinstance(t, (int, float)) else str(t)
    h_text = f'{h:.1f}' if isinstance(h, (int, float)) else str(h)
    return (f"THERMAL GUARD {typ.replace('_TEMP', '').replace('_', ' ')}\n"
            f"Zone: {name}\nTemp: {t_text} C\nHumidity: {h_text}%\n"
            f"Time: {ph_time_text(alert.get('timestamp'))}")


def process_outbox(modem):
    now = time.time()
    for path in store.outbox_items():
        item = store.read_json(path)
        if not isinstance(item, dict):
            print('[GSM] removing unreadable outbox file', path.name, flush=True)
            path.unlink(missing_ok=True)
            continue
        if item.get('next_attempt_at', 0) > now:
            continue
        alert = item.get('alert') or {}
        typ = str(item.get('type') or alert.get('type') or '')
        key = str(item.get('id') or path.stem)
        queued_at = item.get('queued_at')
        age_min = (now * 1000 - float(queued_at if queued_at is not None else now * 1000)) / 60000
        if age_min > config.SMS_MAX_AGE_MINUTES:
            store.append_sms_log({'type': typ, 'status': 'EXPIRED', 'success': False, 'eventKey': key,
                                  'detail': f'Not sent within {config.SMS_MAX_AGE_MINUTES:.0f} min: {item.get("last_error", "")}',
                                  'timestamp': iso(), 'epoch': int(now * 1000)})
            print(f'[GSM] {typ} {key} expired unsent', flush=True)
            path.unlink(missing_ok=True)
            continue

        numbers = item.get('recipients') or recipients_for(typ)
        remaining = [n for n in numbers if n not in item.get('sent_to', [])]
        if not remaining:
            if not numbers:
                print(f'[GSM] {typ} {key}: no SMS recipients set (app SMS off and no SMS_FALLBACK_RECIPIENTS)', flush=True)
            path.unlink(missing_ok=True)
            continue

        message = item.get('message') or alert_message(alert)
        for n in remaining:
            ok, detail = modem.send_sms(n, message)
            log_sms(n, typ, ok, detail, key)
            print(f"[GSM] {typ} -> {n[:6]}*** {'SENT' if ok else 'FAILED: ' + str(detail)[-120:]}", flush=True)
            if ok:
                item.setdefault('sent_to', []).append(n)
            else:
                item['last_error'] = str(detail)[-300:]
                break  # modem problem: retry the rest later
        if all(n in item.get('sent_to', []) for n in numbers):
            path.unlink(missing_ok=True)
        else:
            item['attempts'] = int(item.get('attempts', 0)) + 1
            item['next_attempt_at'] = time.time() + config.SMS_RETRY_SECONDS
            store.write_json(path, item)
            return  # do not hammer a failing modem with the rest of the queue


def check_test_request(modem, st):
    """Test SMS from the app's Settings screen (needs internet)."""
    test = fb.call(lambda: db.reference('/thermal_guard/commands/gsm/test').get(), None) or {}
    rid = str(test.get('requestId') or '')
    if not rid or rid == st.get('last_test_request') or test.get('status') != 'PENDING':
        return
    ref = '/thermal_guard/commands/gsm/test'
    n = store.normalize_number(test.get('recipient'))
    if n:
        fb.call(lambda: db.reference(ref + '/status').set('SENDING'))
        ok, detail = modem.send_sms(n, str(test.get('message') or 'Thermal Guard test SMS'))
        log_sms(n, 'TEST', ok, detail, rid)
        fb.call(lambda: db.reference(ref).update({'status': 'SENT' if ok else 'FAILED',
                                                  'completedAt': int(time.time() * 1000),
                                                  'detail': str(detail)[-300:]}))
    else:
        fb.call(lambda: db.reference(ref).update({'status': 'FAILED', 'detail': 'Invalid recipient'}))
    st['last_test_request'] = rid
    save_state(st)


def main():
    if not config.SIM800L_ENABLED:
        print('[GSM] SIM800L_ENABLED=false; worker idle', flush=True)
        while True:
            time.sleep(3600)
    modem = SIM800L(config.SIM800L_PORT, config.SIM800L_BAUD)
    st = load_state()
    fallback = store.fallback_recipients()
    print(f'[GSM] worker started; {len(fallback)} fallback recipient(s) in .env, '
          f'{len(store.outbox_items())} SMS waiting in outbox', flush=True)
    next_status = 0.0
    while True:
        try:
            if time.time() >= next_status:
                check_modem(modem)
                next_status = time.time() + config.SIM800L_STATUS_SECONDS
            process_outbox(modem)
            check_test_request(modem, st)
            fb.flush_logs()
        except Exception as exc:
            print('GSM worker error:', exc, flush=True)
            try:
                write_status({'error': str(exc)[-300:]})
            except Exception:
                pass
        time.sleep(config.SIM800L_POLL_SECONDS)


if __name__ == '__main__':
    main()
