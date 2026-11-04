import threading
import time
from datetime import datetime, timezone
from . import config


def _now_iso():
    return datetime.now(timezone.utc).isoformat()


class RelayController:
    """GPIO relay used by the physical siren.

    mode:
      on   -> force GPIO output ON
      off  -> force GPIO output OFF
      auto -> follow auto_demand from critical-temperature detection

    Manual OFF is a temporary silence: it returns to AUTO after
    SIREN_OFF_REVERT_MINUTES, or at once when a zone newly reaches CRITICAL
    (see expire_silence / rearm, called by the sensor loop). Without this a
    forgotten OFF would keep every future fire alarm silent.

    The snapshot intentionally separates the requested mode from `on`, which is
    the actual GPIO output state last applied by gpiozero. Android uses both so
    the UI does not claim the siren is active merely because a Firebase command
    was written.
    """

    def __init__(self):
        self._lock = threading.RLock()
        self.mode = 'auto'
        self.auto_demand = False
        self.on = False
        self.available = False
        self.error = None
        self._device = None
        self.last_command_id = ''
        self.last_command_mode = ''
        self.last_command_ok = False
        self.last_command_at = ''
        self.last_requested_by = ''
        self.off_revert_seconds = config.SIREN_OFF_REVERT_MINUTES * 60.0
        self.off_until = None            # time.monotonic() deadline while mode == 'off'
        self.last_auto_revert_reason = ''
        self.last_auto_revert_at = ''
        self._init_device()

    def _init_device(self):
        try:
            from gpiozero import OutputDevice
            self._device = OutputDevice(
                config.RELAY_GPIO,
                active_high=config.RELAY_ACTIVE_HIGH,
                initial_value=False,
            )
            self.available = True
            self.error = None
        except Exception as exc:
            self.available = False
            self.error = str(exc)

    def set_mode(self, mode, request_id='', requested_by=''):
        mode = str(mode).lower().strip()
        if mode not in {'on', 'off', 'auto'}:
            return False
        with self._lock:
            self.mode = mode
            self.off_until = (time.monotonic() + self.off_revert_seconds
                              if mode == 'off' and self.off_revert_seconds > 0 else None)
            applied = self.apply()
            if request_id:
                self.last_command_id = str(request_id)
                self.last_command_mode = mode
                self.last_command_ok = bool(applied)
                self.last_command_at = _now_iso()
                self.last_requested_by = str(requested_by or '')
            return applied

    def _revert_to_auto(self, reason):
        self.mode = 'auto'
        self.off_until = None
        self.last_auto_revert_reason = reason
        self.last_auto_revert_at = _now_iso()
        self.apply()

    def expire_silence(self, now=None):
        """OFF timed out -> AUTO. Returns True if it reverted."""
        with self._lock:
            now = time.monotonic() if now is None else now
            if self.mode == 'off' and self.off_until is not None and now >= self.off_until:
                self._revert_to_auto('timeout')
                return True
            return False

    def rearm(self, reason):
        """A new CRITICAL alarm overrides a manual OFF. Returns True if it reverted."""
        with self._lock:
            if self.mode != 'off':
                return False
            self._revert_to_auto(reason)
            return True

    def set_auto_demand(self, value):
        with self._lock:
            self.auto_demand = bool(value)
            return self.apply()

    def apply(self):
        with self._lock:
            desired = True if self.mode == 'on' else False if self.mode == 'off' else self.auto_demand
            if not self.available or self._device is None:
                self.on = False
                return False
            try:
                self._device.on() if desired else self._device.off()
                self.on = desired
                self.error = None
                return True
            except Exception as exc:
                self.error = str(exc)
                self.available = False
                self.on = False
                return False

    def force_off(self):
        with self._lock:
            try:
                if self._device is not None:
                    self._device.off()
            except Exception as exc:
                self.error = str(exc)
                self.available = False
            finally:
                self.on = False

    def snapshot(self):
        with self._lock:
            return {
                'on': self.on,
                'auto_demand': self.auto_demand,
                'mode': self.mode,
                'gpio': config.RELAY_GPIO,
                'active_high': config.RELAY_ACTIVE_HIGH,
                'available': self.available,
                'error': self.error,
                'last_command_id': self.last_command_id,
                'last_command_mode': self.last_command_mode,
                'last_command_ok': self.last_command_ok,
                'last_command_at': self.last_command_at,
                'last_requested_by': self.last_requested_by,
                'off_revert_minutes': self.off_revert_seconds / 60.0,
                'off_remaining_seconds': (
                    int(max(0.0, self.off_until - time.monotonic()))
                    if self.mode == 'off' and self.off_until is not None else None),
                'last_auto_revert_reason': self.last_auto_revert_reason,
                'last_auto_revert_at': self.last_auto_revert_at,
            }
