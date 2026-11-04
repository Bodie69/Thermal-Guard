"""Manual siren OFF must return to AUTO. Run from /opt/thermal-guard:
    python -m unittest discover -s tests -v
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

try:
    from app import relay as relay_mod
except ImportError as exc:  # pragma: no cover
    raise unittest.SkipTest(f'python-dotenv not installed: {exc}')


class FakeGpio:
    def __init__(self):
        self.value = False

    def on(self):
        self.value = True

    def off(self):
        self.value = False


def make_relay(revert_minutes=30):
    # Build without touching real GPIO, then attach a fake output pin.
    orig = relay_mod.RelayController._init_device
    relay_mod.RelayController._init_device = lambda self: None
    try:
        r = relay_mod.RelayController()
    finally:
        relay_mod.RelayController._init_device = orig
    r._device, r.available = FakeGpio(), True
    r.off_revert_seconds = revert_minutes * 60.0
    return r


class SirenRevertTests(unittest.TestCase):
    def test_off_times_out_to_auto_and_sounds_if_still_critical(self):
        r = make_relay()
        r.set_auto_demand(True)                 # zone is CRITICAL
        r.set_mode('off', request_id='app-1')   # someone silences it
        self.assertFalse(r._device.value)
        t0 = r.off_until - 30 * 60
        self.assertFalse(r.expire_silence(now=t0 + 29 * 60))
        self.assertEqual(r.mode, 'off')
        self.assertTrue(r.expire_silence(now=t0 + 30 * 60))
        self.assertEqual(r.mode, 'auto')
        self.assertTrue(r._device.value, 'still critical -> siren sounds again')
        self.assertEqual(r.snapshot()['last_auto_revert_reason'], 'timeout')

    def test_new_critical_rearms_immediately(self):
        r = make_relay()
        r.set_mode('off')
        self.assertTrue(r.rearm('new CRITICAL: Front'))
        r.set_auto_demand(True)
        self.assertEqual(r.mode, 'auto')
        self.assertTrue(r._device.value)
        self.assertIsNone(r.off_until)

    def test_rearm_does_nothing_unless_off(self):
        for mode in ('auto', 'on'):
            r = make_relay()
            r.set_mode(mode)
            self.assertFalse(r.rearm('x'))
            self.assertFalse(r.expire_silence(now=10 ** 12))
            self.assertEqual(r.mode, mode)

    def test_on_mode_is_not_reverted(self):
        r = make_relay()
        r.set_mode('on')
        self.assertIsNone(r.off_until)
        self.assertTrue(r._device.value)

    def test_zero_minutes_keeps_old_behaviour(self):
        r = make_relay(revert_minutes=0)
        r.set_mode('off')
        self.assertIsNone(r.off_until)
        self.assertFalse(r.expire_silence(now=10 ** 12))
        self.assertEqual(r.mode, 'off')

    def test_snapshot_countdown(self):
        r = make_relay()
        self.assertIsNone(r.snapshot()['off_remaining_seconds'])
        r.set_mode('off')
        left = r.snapshot()['off_remaining_seconds']
        self.assertTrue(1790 <= left <= 1800, left)
        self.assertEqual(r.snapshot()['off_revert_minutes'], 30.0)
        r.set_mode('auto')
        self.assertIsNone(r.snapshot()['off_remaining_seconds'])

    def test_setting_off_again_restarts_timer(self):
        r = make_relay()
        r.set_mode('off')
        first = r.off_until
        r.off_until -= 600
        r.set_mode('off')
        self.assertGreaterEqual(r.off_until, first)


if __name__ == '__main__':
    unittest.main()
