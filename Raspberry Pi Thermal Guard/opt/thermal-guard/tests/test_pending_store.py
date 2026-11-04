"""Unsent Firebase events must survive a restart. Run from /opt/thermal-guard:
    python -m unittest discover -s tests -v
"""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

try:
    from app.pending_store import DurablePending
except ImportError as exc:  # pragma: no cover
    raise unittest.SkipTest(f'python-dotenv not installed: {exc}')

ALERT = {'type': 'CRITICAL_TEMP', 'zone': 'front', 'temperature': 39.1}


class PendingStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name) / 'firebase_outbox'

    def tearDown(self):
        self.tmp.cleanup()

    def files(self):
        return sorted(self.dir.glob('*.json')) if self.dir.exists() else []

    def test_survives_restart_in_order(self):
        q = DurablePending(self.dir, 100)
        q.append(('alert', ALERT, 1000.0))
        q.append(('fcm_alert', ALERT, 1000.5))
        q.append(('history', {'zone_id': 'front', 'payload': {'temperature': 39.1}}, 1001.0))
        self.assertEqual(len(self.files()), 3)

        q2 = DurablePending(self.dir, 100)       # "restart"
        self.assertEqual(len(q2), 3)
        self.assertEqual(q2.loaded, 3)
        self.assertEqual([q2.popleft()[0] for _ in range(3)], ['alert', 'fcm_alert', 'history'])
        self.assertEqual(self.files(), [], 'delivered events are deleted from disk')

    def test_payload_and_time_round_trip(self):
        DurablePending(self.dir, 10).append(('alert', ALERT, 1234.5))
        kind, payload, queued_at = DurablePending(self.dir, 10)[0]
        self.assertEqual((kind, payload, queued_at), ('alert', ALERT, 1234.5))

    def test_peek_does_not_remove(self):
        q = DurablePending(self.dir, 10)
        q.append(('alert', ALERT, 1.0))
        q[0]; q[0]
        self.assertEqual(len(q), 1)
        self.assertEqual(len(self.files()), 1)

    def test_order_kept_across_several_restarts(self):
        q = DurablePending(self.dir, 100)
        q.append(('alert', {'n': 1}, 5.0))
        q = DurablePending(self.dir, 100)
        q.append(('alert', {'n': 2}, 1.0))       # clock went backwards (no RTC)
        q = DurablePending(self.dir, 100)
        q.append(('alert', {'n': 3}, 3.0))
        q = DurablePending(self.dir, 100)
        self.assertEqual([q.popleft()[1]['n'] for _ in range(3)], [1, 2, 3])

    def test_full_queue_drops_history_before_alerts(self):
        q = DurablePending(self.dir, 3)
        q.append(('alert', {'n': 'a1'}, 1.0))
        q.append(('history', {'n': 'h1'}, 2.0))
        q.append(('history', {'n': 'h2'}, 3.0))
        q.append(('alert', {'n': 'a2'}, 4.0))     # over the limit -> h1 goes
        q.append(('alert', {'n': 'a3'}, 5.0))     # -> h2 goes
        self.assertEqual([q[i][1]['n'] for i in range(len(q))], ['a1', 'a2', 'a3'])
        self.assertEqual(len(self.files()), 3)
        q.append(('alert', {'n': 'a4'}, 6.0))     # no history left -> oldest alert
        self.assertEqual([q[i][1]['n'] for i in range(len(q))], ['a2', 'a3', 'a4'])
        self.assertEqual(len(DurablePending(self.dir, 3)), 3)

    def test_corrupt_file_is_skipped_and_removed(self):
        q = DurablePending(self.dir, 10)
        q.append(('alert', ALERT, 1.0))
        bad = self.dir / '000000000099-deadbeef.json'
        bad.write_text('{half a file')                  # power cut mid-write
        (self.dir / '.000000000100.tmp').write_text('x')  # ignored temp file
        q2 = DurablePending(self.dir, 10)
        self.assertEqual(len(q2), 1)
        self.assertFalse(bad.exists())

    def test_disk_failure_keeps_event_in_memory(self):
        self.dir.parent.mkdir(parents=True, exist_ok=True)
        self.dir.write_text('not a directory')   # makes every write fail
        q = DurablePending(self.dir, 10)
        q.append(('alert', ALERT, 1.0))
        self.assertEqual(len(q), 1)
        self.assertEqual(q.popleft()[0], 'alert')

    def test_file_is_plain_json(self):
        DurablePending(self.dir, 10).append(('system_alert', {'type': 'SENSOR_OFFLINE'}, 7.0))
        data = json.loads(self.files()[0].read_text())
        self.assertEqual(data['kind'], 'system_alert')


if __name__ == '__main__':
    unittest.main()
