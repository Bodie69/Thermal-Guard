"""Keep background threads alive.

Every backend thread is a `while True` loop. Without this, one unexpected
exception (a full SD card, a bad Firebase value, a library bug) ends the thread
silently while the API keeps answering - e.g. the heartbeat thread dying would
also take the sensor-stall watchdog with it.

supervised() wraps a loop function: if it raises (or returns), the error and
traceback are logged, `on_crash` is called, and the loop is started again after
a back-off of 1 s, 2 s, 4 s ... up to 30 s. The back-off resets once the loop
has run for a minute without crashing.
"""
import threading
import time
import traceback

_health_lock = threading.Lock()
thread_health = {}      # name -> {'restarts', 'last_error', 'last_crash_at'}

BACKOFF_START = 1.0
BACKOFF_MAX = 30.0
STABLE_SECONDS = 60.0


def health_snapshot():
    with _health_lock:
        return {name: dict(info) for name, info in thread_health.items()}


def _record(name, error):
    with _health_lock:
        info = thread_health.setdefault(name, {'restarts': 0, 'last_error': None,
                                               'last_crash_at': None})
        info['restarts'] += 1
        info['last_error'] = error
        info['last_crash_at'] = time.time()
        return info['restarts']


def supervised(name, target, on_crash=None, sleep=time.sleep, clock=time.monotonic,
               max_restarts=None):
    """Return a function that runs target() forever, restarting it after a crash.

    max_restarts is for tests only (None = restart forever).
    """
    with _health_lock:
        thread_health.setdefault(name, {'restarts': 0, 'last_error': None, 'last_crash_at': None})

    def runner():
        delay = BACKOFF_START
        restarts = 0
        while True:
            started = clock()
            try:
                target()
                error = 'loop returned unexpectedly'
            except BaseException as exc:          # noqa: BLE001 - keep the thread alive
                if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                    raise
                error = f'{type(exc).__name__}: {exc}'
                traceback.print_exc()
            count = _record(name, error)
            if clock() - started >= STABLE_SECONDS:
                delay = BACKOFF_START
            print(f'[THREAD] {name} stopped ({error}); restart #{count} in {delay:.0f}s',
                  flush=True)
            if on_crash is not None:
                try:
                    on_crash(name, error, count)
                except Exception as cb_exc:       # never let reporting kill the thread
                    print(f'[THREAD] crash report for {name} failed: {cb_exc}', flush=True)
            restarts += 1
            if max_restarts is not None and restarts > max_restarts:
                return
            sleep(delay)
            delay = min(delay * 2, BACKOFF_MAX)

    return runner
