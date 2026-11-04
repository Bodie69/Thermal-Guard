"""Rate-limited alerts must reach the app but not the SMS outbox.
Run from /opt/thermal-guard:  python -m unittest discover -s tests -v
"""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

try:
    from app import offline_store
except ImportError as exc:  # pragma: no cover
    raise unittest.SkipTest(f'python-dotenv not installed: {exc}')


class SmsQueueTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self._orig = offline_store.OUTBOX_DIR
        offline_store.OUTBOX_DIR = pathlib.Path(self.tmp.name) / 'sms_outbox'

    def tearDown(self):
        offline_store.OUTBOX_DIR = self._orig
        self.tmp.cleanup()

    def queued(self):
        return sorted(offline_store.OUTBOX_DIR.glob('*.json')) if offline_store.OUTBOX_DIR.exists() else []

    def test_rate_limited_warning_is_not_texted(self):
        offline_store.queue_sms_alert({'type': 'WARNING_TEMP', 'sms': False})
        self.assertEqual(self.queued(), [])

    def test_normal_alerts_are_texted(self):
        offline_store.queue_sms_alert({'type': 'WARNING_TEMP', 'sms': True})
        offline_store.queue_sms_alert({'type': 'CRITICAL_TEMP'})          # older callers: no flag
        self.assertEqual(len(self.queued()), 2)

    def test_system_alerts_never_texted(self):
        offline_store.queue_sms_alert({'type': 'SENSOR_OFFLINE'})
        self.assertEqual(self.queued(), [])


if __name__ == '__main__':
    unittest.main()
