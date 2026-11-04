#!/bin/bash
# Wayland (labwc) version of the Thermal Guard kiosk launcher.
# The display environment is inherited from the desktop autostart.
set -u

URL="http://127.0.0.1:8080/kiosk"
PROFILE="/tmp/thermal-guard-kiosk-profile"
LOG="$HOME/thermal-guard-kiosk.log"

export TZ=Asia/Manila

echo "========================================" >> "$LOG"
echo "[BOOT] $(date)" >> "$LOG"

# Wait for the actual dashboard HTML, not only port 8080.
HTML_READY=0
for i in $(seq 1 600); do
  if curl -fsS --max-time 0.8 "$URL" 2>/dev/null | grep -qi "<html"; then
    echo "[READY] Dashboard HTML attempt $i" >> "$LOG"
    HTML_READY=1
    break
  fi
  sleep 0.2
done

if [ "$HTML_READY" -ne 1 ]; then
  echo "[ERROR] Dashboard HTML did not become ready" >> "$LOG"
  exit 1
fi

# Without an RTC the clock jumps forward when NTP syncs after boot. Starting
# Chromium in the middle of that jump (plus the boot CPU load) left the page
# white. Wait for the sync (max 45 s, so offline boots still start).
# With a DS3231 RTC (/dev/rtc0) the clock is already correct at boot: no wait.
if [ -e /dev/rtc0 ]; then
  echo "[CLOCK] RTC present, time $(date '+%F %T'); skipping NTP wait" >> "$LOG"
else
  for i in $(seq 1 45); do
    [ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" = "yes" ] && break
    sleep 1
  done
  echo "[CLOCK] synced=$(timedatectl show -p NTPSynchronized --value 2>/dev/null) after ${i}s" >> "$LOG"
fi
sleep 3   # let the boot load settle before the heavy Chromium start

# Stop only an old Chromium process launched with this exact Thermal Guard profile.
pkill -f -- "--user-data-dir=$PROFILE" 2>/dev/null || true

# Fresh temporary profile each boot prevents stale profile locks/cache from producing a blank page.
rm -rf "$PROFILE"
mkdir -p "$PROFILE"

echo "[START] Chromium kiosk" >> "$LOG"

exec /usr/lib/chromium/chromium \
  --kiosk \
  --no-first-run \
  --no-default-browser-check \
  --noerrdialogs \
  --disable-session-crashed-bubble \
  --password-store=basic \
  --disable-sync \
  --disable-gpu \
  --disable-gpu-compositing \
  --ozone-platform=wayland \
  --disable-background-networking \
  --disable-component-update \
  --disable-default-apps \
  --disable-extensions \
  --disable-translate \
  --disable-features=Translate,OptimizationHints,OnDeviceModelService,MediaRouter,AutofillServerCommunication,InterestFeedContentSuggestions \
  --renderer-process-limit=2 \
  --user-data-dir="$PROFILE" \
  "$URL" \
  >> "$LOG" 2>&1
