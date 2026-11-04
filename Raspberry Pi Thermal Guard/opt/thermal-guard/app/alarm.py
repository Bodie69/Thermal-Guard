"""Per-zone temperature alarm state machine.

Pure logic (no hardware, no Firebase) so it can be unit tested on any PC:
    python -m unittest discover -s tests

Rules:
  * NORMAL -> WARNING as soon as a reading is >= warning.
  * -> CRITICAL after `critical_confirm` consecutive readings >= critical.
  * Hysteresis: CRITICAL is held until the temperature is below
    (critical - hysteresis) for `recovery_confirm` readings; WARNING is held
    until it is below (warning - hysteresis) for `recovery_confirm` readings.
    A temperature hovering at a threshold therefore cannot flap the siren or
    send an SMS on every crossing.
  * Every WARNING / CRITICAL / RECOVERY goes to the app (push + Alerts list).
    Only the SMS of a WARNING is rate-limited: at most one per zone within
    `warning_realert_seconds`. (Limiting the app push too meant that, with the
    app closed, a WARNING within 10 minutes of the last one never arrived.)
    CRITICAL is always texted.
  * A RECOVERY is texted only if that episode's WARNING/CRITICAL was texted, so
    nobody gets a "recovered" SMS for a problem they were not told about.
  * A missing reading (sensor RETRY/OFFLINE) never lowers the alarm: a
    CRITICAL zone keeps the siren on until real readings show recovery.
"""

NORMAL = 'NORMAL'
WARNING = 'WARNING'
CRITICAL = 'CRITICAL'

WARNING_TEMP = 'WARNING_TEMP'
CRITICAL_TEMP = 'CRITICAL_TEMP'
RECOVERY = 'RECOVERY'


class AlarmEvent(str):
    """An alert type that compares equal to its plain string ('WARNING_TEMP', ...)
    and also says whether it should be sent as an SMS."""

    def __new__(cls, kind, sms=True):
        obj = super().__new__(cls, kind)
        obj.sms = bool(sms)
        return obj


class ZoneAlarm:
    def __init__(self, critical_confirm=3, recovery_confirm=3,
                 hysteresis=0.5, warning_realert_seconds=600.0):
        self.critical_confirm = max(1, int(critical_confirm))
        self.recovery_confirm = max(1, int(recovery_confirm))
        self.hysteresis = max(0.0, float(hysteresis))
        self.warning_realert_seconds = max(0.0, float(warning_realert_seconds))
        self.state = NORMAL
        self.critical_count = 0
        self.recovery_count = 0
        # Per episode: an app alert went out / an SMS went out.
        self.notified = False
        self.sms_notified = False
        self.last_warning_sms_at = None

    @property
    def critical(self):
        return self.state == CRITICAL

    def update(self, temp, warning, critical, now):
        """Feed one valid reading. Returns the AlarmEvents to send (maybe empty)."""
        alerts = []
        h = self.hysteresis

        if self.state == CRITICAL:
            self.critical_count = 0
            if temp >= critical - h:
                self.recovery_count = 0
                return alerts
            self.recovery_count += 1
            if self.recovery_count >= self.recovery_confirm:
                self.recovery_count = 0
                if temp >= warning - h:
                    # Below critical but still warm: siren stops, episode continues.
                    self.state = WARNING
                else:
                    self._to_normal(alerts)
            return alerts

        # NORMAL or WARNING
        self.critical_count = self.critical_count + 1 if temp >= critical else 0
        escalate = self.critical_count >= self.critical_confirm

        if self.state == NORMAL:
            if temp >= warning:
                self.state = WARNING
                self.recovery_count = 0
                # Skip the WARNING alert when CRITICAL fires on this same reading.
                if not escalate:
                    sms = self._warning_sms_allowed(now)
                    if sms:
                        self.last_warning_sms_at = now
                        self.sms_notified = True
                    alerts.append(AlarmEvent(WARNING_TEMP, sms=sms))
                    self.notified = True
        else:  # WARNING
            if temp >= warning - h:
                self.recovery_count = 0
            else:
                self.recovery_count += 1
                if self.recovery_count >= self.recovery_confirm:
                    self._to_normal(alerts)
                    return alerts

        if escalate:
            self.state = CRITICAL
            self.critical_count = 0
            self.recovery_count = 0
            self.notified = True
            self.sms_notified = True
            alerts.append(AlarmEvent(CRITICAL_TEMP, sms=True))
        return alerts

    def missing_reading(self):
        """Sensor gave no valid reading: hold the alarm, restart recovery count."""
        self.recovery_count = 0

    def _warning_sms_allowed(self, now):
        last = self.last_warning_sms_at
        return last is None or now - last >= self.warning_realert_seconds

    def _to_normal(self, alerts):
        self.state = NORMAL
        self.critical_count = 0
        self.recovery_count = 0
        if self.notified:
            alerts.append(AlarmEvent(RECOVERY, sms=self.sms_notified))
        self.notified = False
        self.sms_notified = False
