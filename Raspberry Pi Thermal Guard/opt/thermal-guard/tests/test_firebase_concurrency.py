"""A slow Firebase call must not block the others. Run from /opt/thermal-guard:
    python -m unittest discover -s tests -v

The Firebase Admin SDK is replaced by a fake so no network is used.
"""
import contextlib
import importlib
import io
import pathlib
import sys
import tempfile
import threading
import time
import types
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))


class FakeRef:
    behaviour = {}           # path -> ('slow', seconds) | ('fail', message)
    writes = []              # (path, payload) of every set/update
    data = {}                # path -> value returned by get()
    deletes = []             # paths deleted

    def __init__(self, path):
        self.path = path

    def _act(self, value=None):
        how = FakeRef.behaviour.get(self.path)
        if how and how[0] == 'slow':
            time.sleep(how[1])
        if how and how[0] == 'fail':
            raise ConnectionError(how[1])
        return value

    def get(self):
        return self._act(FakeRef.data.get(self.path, {}))

    def delete(self):
        FakeRef.deletes.append(self.path)
        return self._act(None)

    def set(self, payload):
        FakeRef.writes.append((self.path, payload))
        return self._act(None)

    def update(self, payload):
        FakeRef.writes.append((self.path, payload))
        return self._act(None)

    def push(self, payload):
        return self._act(object())

    def listen(self, callback):
        return FakeRegistration(callback)


class FakeRegistration:
    """Same shape as firebase_admin.db.ListenerRegistration: starts its own
    thread without setting daemon, so the flag is inherited from the caller."""

    def __init__(self, callback):
        self.stop = threading.Event()
        self._sse = types.SimpleNamespace(close=self.stop.set)
        self._thread = threading.Thread(target=self.stop.wait)
        self._thread.start()


def install_fake_sdk():
    fa = types.ModuleType('firebase_admin')
    fa._apps = {}
    fa.initialize_app = lambda cred, opts: fa._apps.setdefault('[DEFAULT]', object())
    creds = types.ModuleType('firebase_admin.credentials')
    creds.Certificate = lambda path: object()
    dbm = types.ModuleType('firebase_admin.db')
    dbm.reference = FakeRef
    msg = types.ModuleType('firebase_admin.messaging')

    class UnregisteredError(Exception):
        pass

    class SenderIdMismatchError(Exception):
        pass

    msg.UnregisteredError, msg.SenderIdMismatchError = UnregisteredError, SenderIdMismatchError
    msg.AndroidConfig = lambda **kw: kw
    msg.Message = lambda **kw: kw
    msg.MulticastMessage = lambda **kw: kw
    msg.sent = []                     # every Message / MulticastMessage sent
    msg.token_results = {}            # token -> exception to report (missing = delivered)

    def send(message):
        msg.sent.append(('topic', message))
        return 'id'

    def send_each_for_multicast(message):
        msg.sent.append(('tokens', message))
        results = [types.SimpleNamespace(success=t not in msg.token_results,
                                         exception=msg.token_results.get(t)) for t in message['tokens']]
        return types.SimpleNamespace(responses=results, success_count=sum(r.success for r in results))

    msg.send, msg.send_each_for_multicast = send, send_each_for_multicast
    fa.credentials, fa.db, fa.messaging = creds, dbm, msg
    sys.modules.update({'firebase_admin': fa, 'firebase_admin.credentials': creds,
                        'firebase_admin.db': dbm, 'firebase_admin.messaging': msg})


try:
    install_fake_sdk()
    from app import config
    import app.firebase_io as firebase_io
    importlib.reload(firebase_io)
except ImportError as exc:  # pragma: no cover
    raise unittest.SkipTest(f'python-dotenv not installed: {exc}')


class FirebaseConcurrencyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        key = pathlib.Path(self.tmp.name) / 'sa.json'
        key.write_text('{}')
        self._orig = (config.FIREBASE_SERVICE_ACCOUNT, config.FIREBASE_DATABASE_URL)
        config.FIREBASE_SERVICE_ACCOUNT = str(key)
        config.FIREBASE_DATABASE_URL = 'https://example.invalid'
        FakeRef.behaviour = {}
        with contextlib.redirect_stdout(io.StringIO()):
            self.fb = firebase_io.FirebaseIO()

    def tearDown(self):
        config.FIREBASE_SERVICE_ACCOUNT, config.FIREBASE_DATABASE_URL = self._orig
        self.tmp.cleanup()

    def test_slow_call_does_not_block_heartbeat(self):
        FakeRef.behaviour['/thermal_guard/system/live'] = ('slow', 1.5)   # hanging live publish
        t = threading.Thread(target=self.fb.set_live, args=({'x': 1},), daemon=True)
        t.start()
        time.sleep(0.1)
        start = time.monotonic()
        ok = self.fb.set_controller_heartbeat({'status': 'ONLINE'})
        elapsed = time.monotonic() - start
        self.assertTrue(ok)
        self.assertLess(elapsed, 0.5, f'heartbeat waited {elapsed:.2f}s behind the slow call')
        t.join()

    def test_slow_call_does_not_block_siren_command(self):
        FakeRef.behaviour['/thermal_guard/alerts'] = ('slow', 1.5)
        t = threading.Thread(target=self.fb.push_ok, args=('/thermal_guard/alerts', {}), daemon=True)
        t.start()
        time.sleep(0.1)
        start = time.monotonic()
        self.fb.get_relay_command()
        self.assertLess(time.monotonic() - start, 0.5)
        t.join()

    def test_heartbeat_result_is_its_own(self):
        # Another thread failing must not make a good heartbeat look failed.
        FakeRef.behaviour['/thermal_guard/alerts'] = ('fail', 'boom')
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(self.fb.push_ok('/thermal_guard/alerts', {}))
            self.assertIsNotNone(self.fb.last_error)
            self.assertTrue(self.fb.set_controller_heartbeat({'status': 'ONLINE'}))
        # ...and a failing heartbeat reports False even if others succeed.
        FakeRef.behaviour = {'/thermal_guard/system/live/controller': ('fail', 'down')}
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(self.fb.set_controller_heartbeat({'status': 'ONLINE'}))
        self.assertTrue(self.fb.push_ok('/thermal_guard/alerts', {}))

    def test_listener_started_from_daemon_thread_is_daemon(self):
        out = {}

        def start():
            out['reg'] = self.fb.listen_relay_command(lambda e: None)

        t = threading.Thread(target=start, daemon=True)
        t.start(); t.join()
        reg = out['reg']
        self.assertIsNotNone(reg)
        self.assertTrue(reg._thread.daemon, 'stream thread must not block process exit')
        reg._sse.close(); reg._thread.join(2)
        self.assertFalse(reg._thread.is_alive())

    def test_heartbeat_and_live_carry_server_timestamp(self):
        FakeRef.writes = []
        controller = {'status': 'ONLINE', 'last_seen_epoch_ms': 123}
        self.fb.set_controller_heartbeat(controller)
        self.fb.set_live({'zones': {}, 'controller': controller})
        self.fb.set_live({'relay': {}})                      # no controller: untouched
        (p1, hb), (p2, live), (p3, other) = FakeRef.writes
        self.assertEqual(p1, '/thermal_guard/system/live/controller')
        self.assertEqual(hb['server_seen'], {'.sv': 'timestamp'})
        self.assertEqual(live['controller']['server_seen'], {'.sv': 'timestamp'})
        self.assertEqual(live['controller']['last_seen_epoch_ms'], 123)
        self.assertNotIn('controller', other)
        self.assertNotIn('server_seen', controller, 'caller dict must not be modified')

    # ---------- pushes go only to active admin/staff phones ----------

    def _users(self, users):
        FakeRef.data = {'/thermal_guard/users': users}
        FakeRef.deletes = []
        msg = sys.modules['firebase_admin.messaging']
        msg.sent, msg.token_results = [], {}
        return msg

    def test_push_goes_only_to_active_staff_and_admin_tokens(self):
        msg = self._users({
            'admin1': {'role': 'admin', 'active': True, 'fcm_tokens': {'a': {'token': 'TOK-ADMIN'}}},
            'staff1': {'role': 'staff', 'active': True, 'fcm_tokens': {'s': {'token': 'TOK-STAFF'},
                                                                        's2': {'token': 'TOK-STAFF-TABLET'}}},
            'off1': {'role': 'staff', 'active': False, 'fcm_tokens': {'x': {'token': 'TOK-DEACTIVATED'}}},
            'odd1': {'role': 'viewer', 'active': True, 'fcm_tokens': {'y': {'token': 'TOK-OTHER-ROLE'}}},
        })
        self.assertTrue(self.fb.send_topic('thermal_guard_alerts', 'T', 'B', {'type': 'CRITICAL_TEMP'}))
        (mode, message), = msg.sent
        self.assertEqual(mode, 'tokens')
        self.assertEqual(sorted(message['tokens']), ['TOK-ADMIN', 'TOK-STAFF', 'TOK-STAFF-TABLET'])
        self.assertEqual(message['data']['type'], 'CRITICAL_TEMP')
        self.assertEqual(message['android'], {'priority': 'high'})

    def test_removed_staff_gets_nothing(self):
        # removeStaffAccess deletes users/{uid}: no record -> no push.
        msg = self._users({'admin1': {'role': 'admin', 'active': True,
                                      'fcm_tokens': {'a': {'token': 'TOK-ADMIN'}}}})
        self.fb.send_topic('thermal_guard_alerts', 'T', 'B', {})
        self.assertEqual(msg.sent[0][1]['tokens'], ['TOK-ADMIN'])

    def test_dead_tokens_are_pruned(self):
        msg = self._users({'admin1': {'role': 'admin', 'active': True, 'fcm_tokens': {
            'good': {'token': 'TOK-GOOD'}, 'gone': {'token': 'TOK-UNINSTALLED'}}}})
        msg.token_results = {'TOK-UNINSTALLED': msg.UnregisteredError()}
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(self.fb.send_topic('thermal_guard_alerts', 'T', 'B', {}))
        self.assertEqual(FakeRef.deletes, ['/thermal_guard/users/admin1/fcm_tokens/gone'])

    def test_all_phones_failing_transiently_keeps_event_for_retry(self):
        msg = self._users({'admin1': {'role': 'admin', 'active': True,
                                      'fcm_tokens': {'a': {'token': 'TOK-ADMIN'}}}})
        msg.token_results = {'TOK-ADMIN': RuntimeError('FCM unavailable')}
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(self.fb.send_topic('thermal_guard_alerts', 'T', 'B', {}))
        self.assertEqual(FakeRef.deletes, [], 'a transient error must not delete the token')

    def test_topic_fallback_until_a_phone_registers(self):
        msg = self._users({'admin1': {'role': 'admin', 'active': True}})       # old app: no tokens
        self.assertTrue(self.fb.send_topic('thermal_guard_system', 'T', 'B', {}))
        (mode, message), = msg.sent
        self.assertEqual((mode, message['topic']), ('topic', 'thermal_guard_system'))

    # ---------- online status (kiosk Firebase indicator) ----------

    def test_online_follows_real_results_not_sdk_ready(self):
        self.assertFalse(self.fb.online, 'no successful call yet')
        self.assertTrue(self.fb.set_controller_heartbeat({'status': 'ONLINE'}))
        self.assertTrue(self.fb.online)

        # Wi-Fi off: calls fail.
        FakeRef.behaviour = {'/thermal_guard/system/live/controller': ('fail', 'Max retries exceeded'),
                             '/thermal_guard/system/live': ('fail', 'Max retries exceeded')}
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(self.fb.set_controller_heartbeat({'status': 'ONLINE'}))
            self.fb.set_live({'zones': {}})
        self.assertFalse(self.fb.online)
        # The old indicator used `ready`, which is restored by the next call
        # without any network access - that is why the kiosk kept saying Online.
        self.fb.ensure_ready()
        self.assertTrue(self.fb.ready, 'ready is only "SDK initialised"')
        self.assertFalse(self.fb.online, 'online must stay False while calls fail')

        # Wi-Fi back: first successful heartbeat brings it back at once.
        FakeRef.behaviour = {}
        self.assertTrue(self.fb.set_controller_heartbeat({'status': 'ONLINE'}))
        self.assertTrue(self.fb.online)

    def test_online_goes_false_when_no_success_for_a_while(self):
        # Wi-Fi up but no internet: requests hang instead of failing.
        self.assertTrue(self.fb.set_controller_heartbeat({'status': 'ONLINE'}))
        self.fb._last_ok_mono -= self.fb.ONLINE_STALE_SECONDS + 1
        self.assertTrue(self.fb.connected, 'nothing has failed yet')
        self.assertFalse(self.fb.online)
        self.assertGreater(self.fb.last_ok_age_seconds(), self.fb.ONLINE_STALE_SECONDS)

    def test_parallel_reinit_is_safe(self):
        self.fb.ready = False
        errors = []

        def go():
            try:
                self.fb.ensure_ready()
            except Exception as exc:     # pragma: no cover
                errors.append(exc)

        threads = [threading.Thread(target=go) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(errors, [])
        self.assertTrue(self.fb.ready)


if __name__ == '__main__':
    unittest.main()
