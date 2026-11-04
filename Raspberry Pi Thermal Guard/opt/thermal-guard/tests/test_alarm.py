"""Alarm state machine tests. Run from /opt/thermal-guard:
    python -m unittest discover -s tests -v
"""
import importlib.util
import pathlib
import unittest

# Load app/alarm.py directly so this runs on any PC (no Pi hardware libraries).
_spec = importlib.util.spec_from_file_location(
    'alarm', pathlib.Path(__file__).resolve().parents[1] / 'app' / 'alarm.py')
alarm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(alarm)

W, C = 35.0, 38.0
CRIT, WARN, REC = alarm.CRITICAL_TEMP, alarm.WARNING_TEMP, alarm.RECOVERY


class Sim:
    """Feeds readings 3 s apart; None = failed sensor read."""

    def __init__(self, **kw):
        self.a = alarm.ZoneAlarm(**{'critical_confirm': 3, 'recovery_confirm': 3,
                                    'hysteresis': 0.5, 'warning_realert_seconds': 600, **kw})
        self.t = 0.0
        self.alerts = []
        self.sms = []
        self.siren = []

    def feed(self, *temps):
        for temp in temps:
            self.t += 3.0
            if temp is None:
                self.a.missing_reading()
            else:
                events = self.a.update(temp, W, C, self.t)
                self.alerts += events
                self.sms += [e for e in events if e.sms]
            self.siren.append(self.a.critical)
        return self


class AlarmTests(unittest.TestCase):
    def test_normal_escalation(self):
        s = Sim().feed(30, 36, 39, 39, 39)
        self.assertEqual(s.alerts, [WARN, CRIT])
        self.assertEqual(s.siren, [False, False, False, False, True])

    def test_critical_hover_does_not_flap(self):
        # Audit bug #2: 37.9 <-> 38.1 used to drop the siren and resend CRITICAL.
        s = Sim().feed(36, 39, 39, 39, *[37.9, 38.1] * 50)
        self.assertEqual(s.alerts, [WARN, CRIT])
        self.assertTrue(all(s.siren[4:]), 'siren must stay on while hovering')

    def test_warning_hover_sms_rate_limited(self):
        # Audit bug #2: 34.9 <-> 35.0 used to send WARNING+RECOVERY every ~12 s.
        s = Sim().feed(*[35.0, 34.9] * 100)          # 10 minutes
        self.assertEqual(s.alerts, [WARN])           # hysteresis holds WARNING
        s = Sim().feed(*([35.0] + [34.0] * 3) * 50)  # real swings, 10 min
        self.assertEqual(s.sms.count(WARN), 1, 'WARNING SMS limited to 1 per 10 min')
        self.assertEqual(s.alerts.count(WARN), 50, 'every new WARNING still reaches the app')

    def test_sensor_glitch_keeps_siren_on(self):
        # Audit bug #1: one failed read during CRITICAL turned the siren off.
        s = Sim().feed(39, 39, 39, None, 39, 39, None, None, 39)
        self.assertTrue(all(s.siren[2:]))
        self.assertEqual(s.alerts.count(CRIT), 1, 'no duplicate CRITICAL SMS')

    def test_dead_sensor_keeps_siren_on(self):
        s = Sim().feed(39, 39, 39, *[None] * 200)
        self.assertTrue(s.siren[-1])
        self.assertEqual(s.alerts, [WARN, CRIT])

    def test_spike_alerts_are_paired(self):
        # Audit bug #3: short spike above critical gave a lone RECOVERY SMS.
        # Now the spike announces WARNING, so the RECOVERY has a matching alert.
        s = Sim().feed(30, 39, 30, 30, 30, 30)
        self.assertEqual(s.alerts, [WARN, REC])
        self.assertEqual(s.a.state, alarm.NORMAL)

    def test_second_warning_within_10_min_pushed_but_not_texted(self):
        # Reported bug: with the app closed no WARNING arrived on repeated tests,
        # because the 10-minute limit also blocked the app push. Now only the
        # SMS is limited (and its RECOVERY SMS with it).
        s = Sim().feed(36, 30, 30, 30, 36, 30, 30, 30)
        self.assertEqual(s.alerts, [WARN, REC, WARN, REC])
        self.assertEqual(s.sms, [WARN, REC])

    def test_critical_always_texted_even_after_recent_warning(self):
        s = Sim().feed(36, 30, 30, 30, 36, 39, 39, 39, 30, 30, 30)
        self.assertEqual(s.alerts, [WARN, REC, WARN, CRIT, REC])
        self.assertEqual(s.sms, [WARN, REC, CRIT, REC], 'CRITICAL (and its recovery) always texted')

    def test_warning_sms_allowed_again_after_10_min(self):
        s = Sim().feed(36, 30, 30, 30)
        s.t += 600
        s.feed(36)
        self.assertEqual(s.sms, [WARN, REC, WARN])

    def test_spike_into_warning_band_is_announced(self):
        # Audit bug #3: NORMAL -> above critical -> warning band sent no alert.
        s = Sim().feed(30, 39, 36, 36)
        self.assertEqual(s.alerts, [WARN])

    def test_critical_recovery_to_normal(self):
        s = Sim().feed(39, 39, 39, 30, 30, 30)
        self.assertEqual(s.alerts, [WARN, CRIT, REC])
        self.assertFalse(s.siren[-1])

    def test_critical_steps_down_to_warning_then_recovers(self):
        s = Sim().feed(39, 39, 39, 36, 36)
        self.assertTrue(s.siren[-1], 'held until recovery confirmed')
        s.feed(36)
        self.assertEqual(s.a.state, alarm.WARNING)
        self.assertFalse(s.siren[-1])
        s.feed(30, 30, 30)
        self.assertEqual(s.alerts, [WARN, CRIT, REC])

    def test_real_recritical_alerts_again(self):
        s = Sim().feed(39, 39, 39, 36, 36, 36, 39, 39, 39)
        self.assertEqual(s.alerts, [WARN, CRIT, CRIT])
        self.assertTrue(s.siren[-1])

    def test_glitch_resets_recovery_count(self):
        s = Sim().feed(39, 39, 39, 30, 30, None, 30, 30)
        self.assertTrue(s.siren[-1], 'recovery needs 3 consecutive good reads')
        s.feed(30)
        self.assertFalse(s.siren[-1])

    def test_immediate_critical_skips_warning(self):
        s = Sim(critical_confirm=1).feed(30, 39)
        self.assertEqual(s.alerts, [CRIT])


if __name__ == '__main__':
    unittest.main()
