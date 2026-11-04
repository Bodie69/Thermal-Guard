import os
from pathlib import Path
from dataclasses import dataclass
from dotenv import load_dotenv

BASE_DIR = Path('/opt/thermal-guard')
load_dotenv(BASE_DIR / '.env')

def _b(name: str, default: bool = False) -> bool:
    return os.getenv(name, str(default)).strip().lower() in {'1','true','yes','on'}

def _i(name: str, default: int) -> int:
    return int(os.getenv(name, str(default)).strip())

def _f(name: str, default: float) -> float:
    return float(os.getenv(name, str(default)).strip())

def _hex_or_int(name: str, default: int) -> int:
    raw = os.getenv(name, hex(default)).strip().lower()
    return int(raw, 0)

FIREBASE_DATABASE_URL = os.getenv('FIREBASE_DATABASE_URL', '').strip()
FIREBASE_SERVICE_ACCOUNT = os.getenv('FIREBASE_SERVICE_ACCOUNT', str(BASE_DIR / 'firebase-service-account.json')).strip()
SENSOR_MODE = os.getenv('SENSOR_MODE', 'sht31').strip().lower()
TCA9548A_ADDRESS = _hex_or_int('TCA9548A_ADDRESS', 0x70)
SHT31_ADDRESS = _hex_or_int('SHT31_ADDRESS', 0x44)
SHT31_SAMPLE_COUNT = max(1, _i('SHT31_SAMPLE_COUNT', 3))
SHT31_SAMPLE_DELAY_SECONDS = max(0.05, _f('SHT31_SAMPLE_DELAY_SECONDS', 0.15))
READ_INTERVAL_SECONDS = _f('READ_INTERVAL_SECONDS', 3.0)
CONTROLLER_HEARTBEAT_SECONDS = max(1.0, _f('CONTROLLER_HEARTBEAT_SECONDS', 5.0))
RELAY_COMMAND_MAX_AGE_SECONDS = max(5.0, _f('RELAY_COMMAND_MAX_AGE_SECONDS', 30.0))
WARNING_TEMP_C = _f('WARNING_TEMP_C', 35.0)
CRITICAL_TEMP_C = _f('CRITICAL_TEMP_C', 38.0)
CRITICAL_CONFIRM_READS = _i('CRITICAL_CONFIRM_READS', 3)
RECOVERY_CONFIRM_READS = _i('RECOVERY_CONFIRM_READS', 3)
OFFLINE_CONFIRM_ERRORS = _i('OFFLINE_CONFIRM_ERRORS', 4)
# A zone only leaves CRITICAL / WARNING once it is this far below the threshold,
# so a temperature hovering at a threshold cannot flap the siren or spam SMS.
ALERT_HYSTERESIS_C = max(0.0, _f('ALERT_HYSTERESIS_C', 0.5))
# Minimum time between two WARNING alerts (and SMS) for the same zone.
WARNING_REALERT_SECONDS = max(0.0, _f('WARNING_REALERT_SECONDS', 600.0))
HISTORY_INTERVAL_SECONDS = _f('HISTORY_INTERVAL_SECONDS', 60.0)
RELAY_GPIO = _i('RELAY_GPIO', 17)
RELAY_ACTIVE_HIGH = _b('RELAY_ACTIVE_HIGH', True)
RELAY_FAILSAFE_OFF_IF_ALL_SENSORS_OFFLINE = _b('RELAY_FAILSAFE_OFF_IF_ALL_SENSORS_OFFLINE', False)
# Manual siren OFF returns to AUTO after this many minutes (0 = stay OFF until
# someone changes it). A zone newly reaching CRITICAL re-arms AUTO immediately.
SIREN_OFF_REVERT_MINUTES = max(0.0, _f('SIREN_OFF_REVERT_MINUTES', 30.0))
SIM800L_ENABLED = _b('SIM800L_ENABLED', True)
SIM800L_PORT = os.getenv('SIM800L_PORT', '/dev/serial0').strip()
SIM800L_BAUD = _i('SIM800L_BAUD', 9600)
SIM800L_POLL_SECONDS = _f('SIM800L_POLL_SECONDS', 3.0)
SIM800L_STATUS_SECONDS = _f('SIM800L_STATUS_SECONDS', 15.0)
FIREBASE_HTTP_TIMEOUT_SECONDS = max(3.0, _f('FIREBASE_HTTP_TIMEOUT_SECONDS', 10.0))
OFFLINE_PENDING_MAX = max(100, _i('OFFLINE_PENDING_MAX', 2000))
SMS_RETRY_SECONDS = max(10.0, _f('SMS_RETRY_SECONDS', 30.0))
SMS_MAX_AGE_MINUTES = max(5.0, _f('SMS_MAX_AGE_MINUTES', 120.0))
HOST = os.getenv('HOST', '0.0.0.0').strip()
PORT = _i('PORT', 8080)

@dataclass(frozen=True)
class Zone:
    id: str
    name: str
    tca_channel: int

ZONES = [
    Zone('entrance', 'Entrance', _i('ENTRANCE_TCA_CHANNEL', 0)),
    Zone('exit', 'Exit', _i('EXIT_TCA_CHANNEL', 1)),
    Zone('front', 'Front', _i('FRONT_TCA_CHANNEL', 2)),
]

# Optional calibration offsets. Keep at 0.0 unless compared against a trusted
# calibrated reference instrument in the same location and conditions.
SENSOR_CALIBRATION = {
    'entrance': (
        _f('ENTRANCE_TEMP_OFFSET', 0.0),
        _f('ENTRANCE_HUM_OFFSET', 0.0),
    ),
    'exit': (
        _f('EXIT_TEMP_OFFSET', 0.0),
        _f('EXIT_HUM_OFFSET', 0.0),
    ),
    'front': (
        _f('FRONT_TEMP_OFFSET', 0.0),
        _f('FRONT_HUM_OFFSET', 0.0),
    ),
}
