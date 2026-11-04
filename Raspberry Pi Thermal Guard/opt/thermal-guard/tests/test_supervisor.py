"""Background threads must restart after a crash. Run from /opt/thermal-guard:
    python -m unittest discover -s tests -v
"""
import contextlib
import importlib.util
import io
import pathlib
import threading
import time
import unittest

_spec = importlib.util.spec_from_file_location(
    'supervisor', pathlib.Path(__file__).resolve().parents[1] / 'app' / 'supervisor.py')
supervisor = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(supervisor)


class FakeClock:
    def __init__(self):
        self.t = 0.0
        self.sleeps = []

    def clock(self):
        return self.t

    def sleep(self, s):
        self.sleeps.append(s)
        self.t += s


def run_quietly(fn):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        fn()


class SupervisorTests(unittest.TestCase):
    def test_crashing_loop_is_restarted_with_backoff(self):
        calls, crashes, fc = [], [], FakeClock()

        def loop():
            calls.append(1)
            raise OSError('disk full')

        runner = supervisor.supervised('t-crash', loop, on_crash=lambda *a: crashes.append(a),
                                       sleep=fc.sleep, clock=fc.clock, max_restarts=6)
        run_quietly(runner)
        self.assertEqual(len(calls), 7, 'first run + 6 restarts')
        self.assertEqual(fc.sleeps, [1, 2, 4, 8, 16, 30])
        self.assertEqual(crashes[0][:2], ('t-crash', 'OSError: disk full'))
        h = supervisor.health_snapshot()['t-crash']
        self.assertEqual(h['restarts'], 7)
        self.assertEqual(h['last_error'], 'OSError: disk full')

    def test_backoff_resets_after_stable_run(self):
        fc, n = FakeClock(), [0]

        def loop():
            n[0] += 1
            if n[0] == 4:
                fc.t += 120          # this run lasted 2 minutes before failing
            raise RuntimeError('boom')

        run_quietly(supervisor.supervised('t-stable', loop, sleep=fc.sleep, clock=fc.clock,
                                          max_restarts=4))
        self.assertEqual(fc.sleeps, [1, 2, 4, 1])

    def test_loop_that_returns_is_restarted(self):
        fc, n = FakeClock(), [0]

        def loop():
            n[0] += 1

        run_quietly(supervisor.supervised('t-return', loop, sleep=fc.sleep, clock=fc.clock,
                                          max_restarts=2))
        self.assertEqual(n[0], 3)
        self.assertEqual(supervisor.health_snapshot()['t-return']['last_error'],
                         'loop returned unexpectedly')

    def test_failing_crash_report_does_not_kill_thread(self):
        fc, n = FakeClock(), [0]

        def loop():
            n[0] += 1
            raise ValueError('x')

        def bad_report(*_):
            raise RuntimeError('report broken')

        run_quietly(supervisor.supervised('t-report', loop, on_crash=bad_report,
                                          sleep=fc.sleep, clock=fc.clock, max_restarts=3))
        self.assertEqual(n[0], 4)

    def test_real_thread_recovers_and_keeps_working(self):
        done = threading.Event()
        n = [0]

        def loop():
            n[0] += 1
            if n[0] < 3:
                raise KeyError('transient')
            done.set()
            while True:
                time.sleep(0.05)

        old = supervisor.BACKOFF_START
        supervisor.BACKOFF_START = 0.01
        try:
            t = threading.Thread(target=supervisor.supervised('t-real', loop), daemon=True)
            run_quietly(lambda: (t.start(), done.wait(5)))
        finally:
            supervisor.BACKOFF_START = old
        self.assertTrue(done.is_set())
        self.assertTrue(t.is_alive())
        self.assertEqual(supervisor.health_snapshot()['t-real']['restarts'], 2)


if __name__ == '__main__':
    unittest.main()
