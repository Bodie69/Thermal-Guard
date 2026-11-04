from pathlib import Path
from datetime import datetime, timezone
import threading
import time
import firebase_admin
from firebase_admin import credentials, db, messaging
from . import config

class FirebaseIO:
    # The heartbeat succeeds every 5 s while online; no success for this long
    # means offline even if no call has failed yet (e.g. a request hanging on a
    # Wi-Fi network without internet, up to FIREBASE_HTTP_TIMEOUT_SECONDS).
    ONLINE_STALE_SECONDS = 20.0

    def __init__(self):
        self.ready = False
        self.last_error = None
        self.last_ok_at = None
        # True after the last Firebase call succeeded. Loops skip Firebase work
        # while it is False; the heartbeat keeps probing and flips it back.
        self.connected = False
        self._last_ok_mono = None      # time.monotonic() of the last successful call
        self.last_push = None
        # Only initialisation is serialised. Firebase calls themselves run in
        # parallel: the Admin SDK is thread-safe, and one slow or hanging call
        # (up to FIREBASE_HTTP_TIMEOUT_SECONDS) must not stall the heartbeat,
        # siren commands and alert publishing behind it.
        self._init_lock = threading.Lock()
        self._init()

    def _init(self):
        with self._init_lock:
            return self._init_locked()

    def _init_locked(self):
        if self.ready:
            return True
        try:
            p = Path(config.FIREBASE_SERVICE_ACCOUNT)
            if not p.exists():
                raise FileNotFoundError(f'Service account not found: {p}')
            if not config.FIREBASE_DATABASE_URL:
                raise RuntimeError('FIREBASE_DATABASE_URL is empty')
            if not firebase_admin._apps:
                cred = credentials.Certificate(str(p))
                # A short timeout makes calls fail fast when the internet is
                # down instead of blocking the publisher for minutes.
                firebase_admin.initialize_app(cred, {
                    'databaseURL': config.FIREBASE_DATABASE_URL,
                    'httpTimeout': config.FIREBASE_HTTP_TIMEOUT_SECONDS,
                })
            self.ready = True
            self.last_error = None
            return True
        except Exception as exc:
            self.ready = False
            self.last_error = str(exc)
            print('Firebase init error:', exc, flush=True)
            return False

    def ensure_ready(self):
        if not self.ready:
            return self._init()
        return True

    def _safe(self, fn, default=None):
        if not self.ensure_ready():
            return default
        try:
            out = fn()
            self._last_ok_mono = time.monotonic()
            self.last_ok_at = datetime.now(timezone.utc).isoformat()
            self.last_error = None
            self.connected = True
            return out
        except Exception as exc:
            self.ready = False
            self.connected = False
            self.last_error = str(exc)
            print('Firebase error:', exc, flush=True)
            return default

    @property
    def online(self):
        """Can the Pi actually reach Firebase right now?

        `ready` only means the SDK is initialised: it flips back to True on the
        next call without touching the network, so it showed "Online" with the
        Wi-Fi off. This uses real results: the last call succeeded and one
        succeeded recently.
        """
        last = self._last_ok_mono
        return bool(self.connected and last is not None
                    and time.monotonic() - last <= self.ONLINE_STALE_SECONDS)

    def last_ok_age_seconds(self):
        last = self._last_ok_mono
        return None if last is None else round(time.monotonic() - last, 1)

    def _safe_ok(self, fn):
        """Run fn and report whether this call succeeded (not another thread's)."""
        failed = object()
        return self._safe(fn, failed) is not failed

    def push_ok(self, path, payload):
        """Push and report success, so offline events can be kept and replayed."""
        return self._safe_ok(lambda: db.reference(path).push(payload))

    def get_runtime_settings(self):
        return self._safe(lambda: db.reference('/thermal_guard/settings').get() or {}, {})

    def get_relay_command(self):
        data = self._safe(lambda: db.reference('/thermal_guard/commands/relay').get() or {}, {})
        if not isinstance(data, dict):
            return None
        mode = str(data.get('mode', '')).lower().strip()
        if mode not in ('on', 'off', 'auto'):
            return None
        return {
            'mode': mode,
            'requestId': str(data.get('requestId') or ''),
            'requestedBy': str(data.get('requestedBy') or ''),
            'requestedAt': data.get('requestedAt'),
        }

    def listen_relay_command(self, callback):
        """Stream changes of /thermal_guard/commands/relay to callback(event).

        Returns the ListenerRegistration, or None if it could not start. The
        SDK starts its own thread for the stream; it inherits the daemon flag
        of the calling thread, so call this from a daemon thread.
        """
        if not self.ensure_ready():
            return None
        try:
            return db.reference('/thermal_guard/commands/relay').listen(callback)
        except Exception as exc:
            print('Firebase relay listener could not start:', exc, flush=True)
            return None

    def set_relay_ack(self, payload):
        return self._safe(lambda: db.reference('/thermal_guard/commands/relay/ack').set(payload))

    def set_relay_live(self, payload):
        return self._safe(lambda: db.reference('/thermal_guard/system/live/relay').update(payload))

    # Firebase replaces this placeholder with its own clock when the write lands.
    # The Android app compares heartbeat age on that server clock, so a wrong Pi
    # clock (no RTC) or a wrong phone clock can no longer hide a live Pi.
    SERVER_TIMESTAMP = {'.sv': 'timestamp'}

    @classmethod
    def _stamp_controller(cls, controller):
        return {**controller, 'server_seen': cls.SERVER_TIMESTAMP}

    def set_live(self, payload):
        if isinstance(payload.get('controller'), dict):
            payload = {**payload, 'controller': self._stamp_controller(payload['controller'])}
        return self._safe(lambda: db.reference('/thermal_guard/system/live').update(payload))

    def set_controller_heartbeat(self, payload):
        """Returns True when this heartbeat reached Firebase."""
        stamped = self._stamp_controller(payload)
        return self._safe_ok(lambda: db.reference('/thermal_guard/system/live/controller').set(stamped))

    def push_history(self, zone_id, payload):
        return self._safe(lambda: db.reference(f'/thermal_guard/history/{zone_id}').push(payload))

    def push_alert(self, payload):
        return self._safe(lambda: db.reference('/thermal_guard/alerts').push(payload))

    def push_system_alert(self, payload):
        return self._safe(lambda: db.reference('/thermal_guard/system_alerts').push(payload))

    # Roles whose phones receive alarm pushes. Checked at send time, so removing
    # or deactivating someone in the app stops their pushes from the next alert.
    PUSH_ROLES = ('admin', 'staff')

    def _push_recipients(self):
        """[(uid, token_id, token)] for every active admin/staff phone."""
        users = db.reference('/thermal_guard/users').get() or {}
        out, seen = [], set()
        for uid, user in users.items():
            if not isinstance(user, dict) or user.get('active') is not True:
                continue
            if str(user.get('role', '')).strip().lower() not in self.PUSH_ROLES:
                continue
            for token_id, entry in (user.get('fcm_tokens') or {}).items():
                token = entry.get('token') if isinstance(entry, dict) else None
                if isinstance(token, str) and token and token not in seen:
                    seen.add(token)
                    out.append((uid, token_id, token))
        return out

    def send_topic(self, topic, title, body, data=None):
        """Send a high-priority data-only FCM message to every active admin/staff phone.

        Data-only delivery lets ThermalMessagingService execute while the Android
        app is backgrounded, so the uploaded WARNING/CRITICAL audio and STOP APP
        SOUND notification action work instead of Android showing a generic
        notification without running app code.

        Pushes go to the tokens registered under /thermal_guard/users/{uid}/fcm_tokens
        of users who are active admin/staff right now. FCM topics cannot be
        revoked server-side, so a removed staff member's phone kept receiving
        alarms. `topic` is only used while no phone has registered a token yet
        (older app versions), so nobody misses an alarm during the update.
        Tokens FCM reports as dead are deleted.
        """
        def _send():
            payload = {str(k): str(v) for k, v in (data or {}).items()}
            payload['title'] = str(title)
            payload['body'] = str(body)
            android = messaging.AndroidConfig(priority='high')
            recipients = self._push_recipients()
            if not recipients:
                messaging.send(messaging.Message(data=payload, topic=topic, android=android))
                self.last_push = {'mode': 'topic', 'topic': topic, 'at': datetime.now(timezone.utc).isoformat()}
                return True
            resp = messaging.send_each_for_multicast(messaging.MulticastMessage(
                tokens=[token for _, _, token in recipients], data=payload, android=android))
            dead, transient = [], 0
            for (uid, token_id, _), result in zip(recipients, resp.responses):
                if result.success:
                    continue
                if isinstance(result.exception, (messaging.UnregisteredError, messaging.SenderIdMismatchError)):
                    dead.append((uid, token_id))
                else:
                    transient += 1
            for uid, token_id in dead:
                db.reference(f'/thermal_guard/users/{uid}/fcm_tokens/{token_id}').delete()
            self.last_push = {'mode': 'tokens', 'phones': len(recipients), 'delivered': resp.success_count,
                              'dead_removed': len(dead), 'at': datetime.now(timezone.utc).isoformat()}
            if dead:
                print(f'[PUSH] removed {len(dead)} dead phone token(s)', flush=True)
            if resp.success_count == 0 and transient:
                # Nothing delivered for a retryable reason: keep the event queued.
                raise RuntimeError(f'FCM delivery failed for all {transient} phone(s)')
            return True
        return self._safe_ok(_send)
