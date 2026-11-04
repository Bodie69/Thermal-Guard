# Thermal Guard

Real-time temperature and humidity monitoring for a room (ComLab 1), with a
siren, SMS alerts and an Android app.

A Raspberry Pi reads three SHT31 sensors (Entrance, Exit, Front). It shows them
on a touchscreen kiosk and publishes them to Firebase. When a zone gets too
hot, it switches on the siren, pushes an alert to staff phones and sends an SMS
through a SIM800L modem. The SMS goes out even if the internet is down.

```
 SHT31 x3 ──► TCA9548A ──► Raspberry Pi ──► siren relay (GPIO 17)
                              │   │
                              │   └──► SIM800L ──► SMS (works offline)
                              ▼
                          Firebase ◄──► Android app (admin / staff)
                              ▲
     kiosk touchscreen ───────┘  http://<pi-ip>:8080  (also opens on phones)
```

## Repository layout

| Path | What it is |
|---|---|
| `Raspberry Pi Thermal Guard/opt/thermal-guard/` | Backend (FastAPI): sensors, alarm logic, siren relay, Firebase sync, kiosk dashboard |
| `…/opt/thermal-guard/gsm_worker.py` | SIM800L SMS worker (separate service) |
| `…/opt/thermal-guard/scripts/` | Hardware self-tests, RTC setup, `add_admin.py` |
| `…/opt/thermal-guard/tests/` | Unit tests (alarm, siren revert, SMS queue/masking, Wi-Fi access, …) |
| `Raspberry Pi Thermal Guard/etc/systemd/system/` | `thermal-guard.service`, `thermal-guard-gsm.service` |
| `Raspberry Pi Thermal Guard/home/thermalguard/` | Chromium kiosk launcher and watchdog (autostart) |
| `Raspberry Pi Thermal Guard/usr/local/sbin/thermal-guard-wifi` | Root helper for Wi-Fi changes from the kiosk |
| `Thermal Guard App/THERMAL_GUARD APP/` | Android app (Kotlin, `com.thermalguard.app`, Android 8.0+) |
| `Thermal Guard App/THERMAL_GUARD APP/database.rules.json` | Firebase Realtime Database security rules |

The Pi folder mirrors where each file goes on the Pi (`/opt`, `/etc`, `/home`, `/usr`).

## Hardware

| Part | Connection / default |
|---|---|
| Raspberry Pi with 800×480 touchscreen | Runs the backend and the kiosk |
| 3 × SHT31 sensors | I²C address `0x44`, behind a TCA9548A |
| TCA9548A I²C multiplexer | `0x70`; Entrance = channel 0, Exit = 1, Front = 2 |
| Siren relay | GPIO 17, active-low (`RELAY_ACTIVE_HIGH=false`) |
| SIM800L GSM modem | `/dev/serial0`, 9600 baud |
| DS3231 RTC (optional) | Keeps the correct time offline (`scripts/setup_rtc.sh`) |

## Alarm behaviour

- **WARNING** at 35.0 °C, **CRITICAL** at 38.0 °C. Change these in `.env`, or from the app.
- CRITICAL needs 3 readings in a row to trigger, and recovery needs 3 normal readings, so one bad reading doesn't trigger or clear the alarm.
- **Siren modes:**
  - **AUTO**: the siren sounds on CRITICAL.
  - **ON**: the siren sounds until changed.
  - **OFF**: silenced, but it goes back to AUTO after 30 minutes, or at once if a zone newly reaches CRITICAL.
- **Alerts:**
  - Push notifications go to active `admin`/`staff` users in the app.
  - SMS goes to the app's SMS list, plus any always-on numbers in `SMS_FALLBACK_RECIPIENTS`.

## Raspberry Pi setup

```bash
# 1. Copy the files to the same paths on the Pi.
sudo cp -r "Raspberry Pi Thermal Guard/opt/thermal-guard" /opt/
sudo cp "Raspberry Pi Thermal Guard/etc/systemd/system/"*.service /etc/systemd/system/
sudo install -m 755 -o root -g root "Raspberry Pi Thermal Guard/usr/local/sbin/thermal-guard-wifi" /usr/local/sbin/
cp -r "Raspberry Pi Thermal Guard/home/thermalguard/." /home/thermalguard/
sudo chown -R thermalguard:thermalguard /opt/thermal-guard

# 2. Python environment
cd /opt/thermal-guard
python3 -m venv venv
venv/bin/pip install -r requirements.txt

# 3. Configuration (not in git)
cp .env.example .env        # then edit: Firebase URL, SMS numbers, thresholds
# put the Firebase service-account key at /opt/thermal-guard/firebase-service-account.json

# 4. Start
sudo systemctl daemon-reload
sudo systemctl enable --now thermal-guard thermal-guard-gsm
```

The Wi-Fi helper needs a sudoers rule. It lets the `thermalguard` user run
**only** `/usr/local/sbin/thermal-guard-wifi` as root without a password:

```
# /etc/sudoers.d/thermal-guard-wifi   (edit with: sudo visudo -f /etc/sudoers.d/thermal-guard-wifi)
thermalguard ALL=(root) NOPASSWD: /usr/local/sbin/thermal-guard-wifi
```

**Hardware checks** (run from `/opt/thermal-guard`):

```bash
venv/bin/python scripts/sht31_tca_self_test.py     # sensors
venv/bin/python scripts/sim800l_self_test.py       # GSM modem
venv/bin/python scripts/verify_realtime_chain.py   # Pi -> Firebase in sync
```

**Tests:**

```bash
cd /opt/thermal-guard && venv/bin/python -m unittest discover tests
```

## Dashboard and local API

Open `http://<pi-ip>:8080` on the kiosk or on any phone on the same network.
Phones get a stacked, scrolling layout.

| Endpoint | Access |
|---|---|
| `GET /` | Redirects to `/kiosk` |
| `GET /kiosk` | Dashboard |
| `GET /api/live`, `/health`, `/ready`, `/api/sms`, `/api/wifi/status` | Read-only, no login. Phone numbers in `/api/sms` are masked |
| `POST /api/siren/mode` | AUTO / ON / OFF from any device that opens the dashboard; each change is logged with its source |
| `/api/wifi/*` (on, off, scan, connect) | Kiosk screen only (`127.0.0.1`) |

There is no login on port 8080. Anyone on the same network can read the
readings and switch the siren. Keep the Pi on a trusted network, or restrict
port 8080 with a firewall.

## Android app

1. Open `Thermal Guard App/THERMAL_GUARD APP` in Android Studio.
2. Download `google-services.json` from the Firebase console (Project settings → Your apps) and put it in `app/`. It's not committed; see `app/google-services.json.example`.
3. Build and run (`compileSdk 35`, `minSdk 26`).

Users are stored at `/thermal_guard/users/<uid>` in the Realtime Database,
with `role` (`admin` or `staff`) and `active: true`. To make someone an admin:

```bash
cd /opt/thermal-guard && venv/bin/python -m scripts.add_admin <firebase-uid>
```

or add `role: "admin"` and `active: true` under their UID in the Firebase console.

To apply the security rules, paste `database.rules.json` into the Firebase
console: **Realtime Database → Rules → Publish**.

## Not in this repository

These are kept out on purpose (see `.gitignore`):

- `.env`
- the Firebase service-account key
- `google-services.json`
- `local.properties`
- builds and APKs
- local backups
