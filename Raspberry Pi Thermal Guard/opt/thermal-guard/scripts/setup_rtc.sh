#!/bin/bash
# Thermal Guard - DS3231 real-time clock setup.
#
# Step 1 (now, before the RTC is plugged in):
#     sudo bash /opt/thermal-guard/scripts/setup_rtc.sh prepare
#   Enables I2C + the DS3231 driver, installs hwclock/i2c-tools and a daily
#   RTC sync job. Safe to run without the module: nothing changes the clock yet.
#
# Then: shut down, wire the DS3231 (3.3V, GND, SDA=pin 3, SCL=pin 5), boot.
#
# Step 2 (after the RTC is plugged in, while the Pi is ONLINE):
#     sudo bash /opt/thermal-guard/scripts/setup_rtc.sh finish
#   Checks the RTC is detected, switches the boot clock from fake-hwclock to
#   the RTC and writes the correct internet time into it.
#
# Check any time:
#     sudo bash /opt/thermal-guard/scripts/setup_rtc.sh status
set -u

OVERLAY="dtoverlay=i2c-rtc,ds3231"
HWCLOCK_SET=/lib/udev/hwclock-set
CRON_FILE=/etc/cron.d/thermal-guard-rtc-sync

say()  { echo "[RTC] $*"; }
fail() { echo "[RTC] ERROR: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || fail "run with sudo"

boot_config() {
  if [ -f /boot/firmware/config.txt ]; then echo /boot/firmware/config.txt
  elif [ -f /boot/config.txt ]; then echo /boot/config.txt
  else fail "cannot find config.txt"; fi
}

ntp_synced() { [ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" = "yes" ]; }

prepare() {
  local cfg; cfg=$(boot_config)

  # Tools: hwclock lives in util-linux-extra on Bookworm.
  if ! command -v hwclock >/dev/null || ! command -v i2cdetect >/dev/null; then
    say "installing hwclock and i2c-tools (needs internet)"
    apt-get update -qq && apt-get install -y -qq util-linux-extra i2c-tools \
      || apt-get install -y -qq i2c-tools \
      || say "WARNING: package install failed - run 'prepare' again when online"
  fi

  # I2C is already used by the TCA9548A, but make sure it is on.
  if command -v raspi-config >/dev/null; then raspi-config nonint do_i2c 0 || true; fi

  if grep -qE '^\s*dtoverlay=i2c-rtc' "$cfg"; then
    say "RTC overlay already in $cfg"
  else
    cp -a "$cfg" "$cfg.before-rtc"
    printf '\n# Thermal Guard: DS3231 real-time clock on I2C bus 1\n%s\n' "$OVERLAY" >> "$cfg"
    say "added '$OVERLAY' to $cfg (backup: $cfg.before-rtc)"
  fi

  # Daily: when the internet time is good, copy it into the RTC (keeps drift ~0).
  cat > "$CRON_FILE" <<'EOF'
# Thermal Guard: refresh the DS3231 from network time once a day when online.
17 3 * * * root [ -e /dev/rtc0 ] && [ "$(timedatectl show -p NTPSynchronized --value)" = "yes" ] && /sbin/hwclock -w
EOF
  chmod 644 "$CRON_FILE"
  say "installed daily RTC sync: $CRON_FILE"

  echo
  say "Prepared. Next:"
  say "  1. sudo shutdown -h now   (unplug power)"
  say "  2. Wire DS3231: VCC->pin 1 (3.3V), GND->pin 6, SDA->pin 3, SCL->pin 5"
  say "  3. Power on, connect to the internet, then run:"
  say "     sudo bash $0 finish"
}

finish() {
  [ -e /dev/rtc0 ] || {
    say "no /dev/rtc0 - the RTC is not detected."
    command -v i2cdetect >/dev/null && i2cdetect -y 1
    fail "check wiring (0x68 should appear as 'UU'), and that 'prepare' was run and the Pi rebooted"
  }
  say "RTC detected: $(cat /sys/class/rtc/rtc0/name 2>/dev/null)"

  # fake-hwclock restores the last shutdown time at boot; the RTC replaces it.
  if dpkg -s fake-hwclock >/dev/null 2>&1; then
    systemctl disable --now fake-hwclock 2>/dev/null || true
    apt-get purge -y -qq fake-hwclock || say "WARNING: could not remove fake-hwclock"
    say "removed fake-hwclock"
  fi

  # Older images skip loading the RTC at boot when systemd runs; enable it.
  if [ -f "$HWCLOCK_SET" ] && grep -qE '^if \[ -e /run/systemd/system \] ; then' "$HWCLOCK_SET"; then
    cp -a "$HWCLOCK_SET" "$HWCLOCK_SET.before-rtc"
    sed -i '/^if \[ -e \/run\/systemd\/system \] ; then$/,/^fi$/ s/^/#/' "$HWCLOCK_SET"
    say "patched $HWCLOCK_SET (backup: $HWCLOCK_SET.before-rtc)"
  fi

  if ntp_synced; then
    hwclock -w && say "wrote internet time into the RTC"
  else
    say "WARNING: no internet time yet - the RTC was NOT set."
    say "         Connect to the internet and run: sudo hwclock -w"
  fi
  status
  say "Done. Test: turn Wi-Fi off, reboot, and run 'date'."
}

status() {
  echo
  say "system time : $(date '+%F %T %Z')"
  if [ -e /dev/rtc0 ]; then
    say "RTC time    : $(hwclock -r 2>/dev/null || echo 'unreadable')"
  else
    say "RTC time    : no RTC detected (/dev/rtc0 missing)"
  fi
  say "NTP synced  : $(timedatectl show -p NTPSynchronized --value 2>/dev/null)"
  say "overlay     : $(grep -E '^\s*dtoverlay=i2c-rtc' "$(boot_config)" || echo 'not configured')"
}

case "${1:-}" in
  prepare) prepare ;;
  finish)  finish ;;
  status)  status ;;
  *) echo "Usage: sudo bash $0 prepare|finish|status"; exit 2 ;;
esac
