#!/bin/bash
# Thermal Guard kiosk watchdog (Wayland / labwc session).
# Restarts the kiosk browser when the dashboard page is not polling the backend:
# a page that never loaded (white screen), an "Aw, Snap!" renderer crash, a
# frozen page, or Chromium not running at all.
# If the backend itself is down nothing is restarted - the page recovers on its own.
#
# All timing uses uptime (monotonic), never the wall clock: the Pi has no RTC,
# so the clock jumps forward when it syncs over the network after boot. Using
# `date` made the watchdog kill a freshly started browser right after boot.
set -u

START="$HOME/start-thermal-guard-kiosk.sh"
PROFILE="/tmp/thermal-guard-kiosk-profile"
STATUS_URL="http://127.0.0.1:8080/api/kiosk/status"
LOG="$HOME/thermal-guard-kiosk.log"

CHECK_EVERY=10        # seconds between checks
BROWSER_GRACE=75      # a browser younger than this is still loading (Pi 3 is slow)
STALE_AFTER=90        # page silent this long = broken (it polls every 3 s; requests time out after 6 s)
MIN_RESTART_GAP=90    # never restart more often than this

# Only one watchdog per session.
exec 9>"/tmp/thermal-guard-kiosk-watchdog.lock"
flock -n 9 || exit 0

log() { echo "[WATCHDOG] $(date '+%F %T') $*" >> "$LOG"; }
uptime_s() { cut -d. -f1 /proc/uptime; }

# Age in seconds of the kiosk browser (oldest process with our profile), empty if none.
browser_age() {
  local pid
  pid=$(pgrep -o -f -- "--user-data-dir=$PROFILE") || return 0
  ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' '
}

last_restart=$(uptime_s)
log "started"

restart_kiosk() {
  log "restarting kiosk: $1"
  last_restart=$(uptime_s)
  setsid nohup "$START" >/dev/null 2>&1 < /dev/null &
}

while sleep "$CHECK_EVERY"; do
  since_restart=$(( $(uptime_s) - last_restart ))
  [ "$since_restart" -lt "$MIN_RESTART_GAP" ] && continue

  # The launcher is still waiting for the backend / clock sync: leave it alone.
  pgrep -f "[s]tart-thermal-guard-kiosk.sh" >/dev/null && continue

  age_browser=$(browser_age)
  if [ -z "$age_browser" ]; then
    restart_kiosk "browser is not running"
    continue
  fi
  [ "$age_browser" -lt "$BROWSER_GRACE" ] && continue

  status=$(curl -fsS --max-time 3 "$STATUS_URL" 2>/dev/null) || continue   # backend down: wait
  age=$(printf '%s' "$status" | sed -n 's/.*"seconds_since_poll"[[:space:]]*:[[:space:]]*\([0-9.]*\).*/\1/p')
  age=${age%.*}

  # No poll since the backend (re)started. A healthy page polls every 3 s, so a
  # browser this old with no polls never loaded the page (white screen).
  if [ -z "$age" ]; then
    restart_kiosk "dashboard page never loaded (browser up ${age_browser}s, no polls)"
    continue
  fi

  if [ "$age" -ge "$STALE_AFTER" ]; then
    restart_kiosk "dashboard page silent for ${age}s (crashed or frozen)"
  fi
done
