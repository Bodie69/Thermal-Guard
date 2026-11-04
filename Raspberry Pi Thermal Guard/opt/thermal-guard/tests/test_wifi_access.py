"""Wi-Fi endpoints must be kiosk-only. Run from /opt/thermal-guard:
    python -m unittest discover -s tests -v
Needs fastapi + httpx (already in the Pi venv); skipped otherwise.
"""
import sys
import pathlib
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

try:
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app import wifi_api
    from app.local_access import is_local_client
except ImportError as exc:  # pragma: no cover
    raise unittest.SkipTest(f'fastapi/httpx not installed: {exc}')

CHANGE_ROUTES = [
    ('post', '/api/wifi/on', None), ('post', '/api/wifi/enable', None), ('post', '/wifi/on', None),
    ('post', '/api/network/wifi/on', None), ('post', '/api/network/wifi/enable', None),
    ('post', '/api/wifi/off', None), ('post', '/api/wifi/disable', None), ('post', '/wifi/off', None),
    ('post', '/api/network/wifi/off', None), ('post', '/api/network/wifi/disable', None),
    ('post', '/api/wifi/toggle', None), ('post', '/wifi/toggle', None), ('post', '/api/network/wifi/toggle', None),
    ('get', '/api/wifi/scan', None), ('get', '/api/wifi/networks', None), ('get', '/wifi/scan', None),
    ('get', '/api/network/wifi/scan', None), ('get', '/api/network/wifi/networks', None),
    ('post', '/api/wifi/connect', {'ssid': 'x', 'password': 'y'}),
    ('post', '/wifi/connect', {'ssid': 'x', 'password': 'y'}),
    ('post', '/api/network/wifi/connect', {'ssid': 'x', 'password': 'y'}),
]


class WifiAccessTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self._orig = (wifi_api.run, wifi_api.privileged)
        # Never touch the real nmcli/sudo: record what would have run.
        wifi_api.privileged = lambda *a, **k: self.calls.append(a) or ''
        wifi_api.run = lambda args, **k: self.calls.append(tuple(args)) or 'enabled'
        app = FastAPI()
        app.include_router(wifi_api.router)
        self.app = app

    def tearDown(self):
        wifi_api.run, wifi_api.privileged = self._orig

    def client(self, host):
        return TestClient(self.app, client=(host, 50000))

    def test_lan_client_blocked_from_every_change_route(self):
        c = self.client('192.168.1.50')
        for method, path, body in CHANGE_ROUTES:
            r = getattr(c, method)(path, json=body) if body else getattr(c, method)(path)
            self.assertEqual(r.status_code, 403, f'{method.upper()} {path}')
        self.assertEqual(self.calls, [], 'no Wi-Fi command may run for a LAN client')

    def test_tailscale_client_blocked(self):
        r = self.client('100.101.102.103').post('/api/wifi/off')
        self.assertEqual(r.status_code, 403)
        self.assertEqual(self.calls, [])

    def test_kiosk_allowed(self):
        for host in ('127.0.0.1', '::1'):
            self.calls.clear()
            r = self.client(host).post('/api/wifi/off')
            self.assertEqual(r.status_code, 200, host)
            self.assertIn(('off',), self.calls)

    def test_password_sent_on_stdin_not_argv(self):
        seen = []
        wifi_api.privileged = lambda *a, **k: seen.append((a, k)) or ''
        r = self.client('127.0.0.1').post('/api/wifi/connect',
                                          json={'ssid': 'Cafe', 'password': 's3cret pass'})
        self.assertEqual(r.status_code, 200)
        args, kwargs = seen[0]
        self.assertEqual(args, ('connect', 'Cafe'))
        self.assertNotIn('s3cret pass', ' '.join(args))
        self.assertEqual(kwargs.get('input'), 's3cret pass\n')

    def test_status_still_readable_from_lan(self):
        r = self.client('192.168.1.50').get('/api/wifi/status')
        self.assertEqual(r.status_code, 200)

    def test_loopback_detection(self):
        class Req:
            def __init__(self, host):
                self.client = type('C', (), {'host': host})()
        for host, ok in [('127.0.0.1', True), ('127.0.1.1', True), ('::1', True),
                         ('::ffff:127.0.0.1', True), ('localhost', True),
                         ('192.168.1.5', False), ('100.64.0.1', False), ('', False),
                         ('testclient', False), ('::ffff:192.168.1.5', False)]:
            self.assertEqual(is_local_client(Req(host)), ok, host)


if __name__ == '__main__':
    unittest.main()
