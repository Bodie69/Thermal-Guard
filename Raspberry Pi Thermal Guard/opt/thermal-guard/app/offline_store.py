"""Local storage that keeps Thermal Guard working without internet.

The sensor service writes every temperature alert to a local SMS outbox on the
SD card, and the GSM worker sends it through the SIM800L. Neither step needs
Firebase. Firebase settings are cached here whenever they can be read, so the
last known thresholds and SMS recipients survive an outage and a reboot.
"""
import json
import os
import re
import time
import uuid
from pathlib import Path

from . import config

DATA_DIR = config.BASE_DIR / 'data'
OUTBOX_DIR = DATA_DIR / 'sms_outbox'
SETTINGS_CACHE = DATA_DIR / 'settings_cache.json'
SMS_LOG = DATA_DIR / 'sms_log.jsonl'
GSM_STATUS = DATA_DIR / 'gsm_status.json'

SMS_ALERT_TYPES = ('WARNING_TEMP', 'CRITICAL_TEMP', 'RECOVERY')


def write_json(path: Path, data) -> None:
    """Write atomically so a power cut never leaves a half-written file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    tmp.write_text(json.dumps(data, separators=(',', ':')))
    os.replace(tmp, path)


def read_json(path: Path, default=None):
    try:
        return json.loads(path.read_text())
    except Exception:
        return default


# ---------- phone numbers ----------

def normalize_number(value):
    """Return an E.164 number (+639171234567) or None.

    Accepts +<digits>, and Philippine local forms 09xxxxxxxxx / 9xxxxxxxxx /
    639xxxxxxxxx so .env entries can be typed the way people write them.
    """
    s = re.sub(r'[\s\-().]', '', str(value or ''))
    if not s:
        return None
    if s.startswith('+'):
        digits = s[1:]
    elif s.startswith('09') and len(s) == 11:
        digits = '63' + s[1:]
    elif s.startswith('9') and len(s) == 10:
        digits = '63' + s
    elif s.startswith('63') and len(s) == 12:
        digits = s
    else:
        return None
    if digits.isdigit() and 8 <= len(digits) <= 15:
        return '+' + digits
    return None


# Stored numbers are always E.164 (+ and digits), and modem echoes such as
# AT+CMGS="+639171234567" use the same form. Epoch values and event keys have
# no leading '+', so they are never touched.
_PHONE_RE = re.compile(r'\+\d{8,15}')


def mask_number(number: str) -> str:
    """+639171234567 -> +63*******567 (country code and last 3 digits only)."""
    digits = number[1:]
    return '+' + digits[:2] + '*' * max(0, len(digits) - 5) + digits[-3:]


def mask_phone_numbers(value):
    """Return a copy of value with every phone number in any string masked."""
    if isinstance(value, str):
        return _PHONE_RE.sub(lambda m: mask_number(m.group(0)), value)
    if isinstance(value, dict):
        return {k: mask_phone_numbers(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [mask_phone_numbers(v) for v in value]
    return value


def fallback_recipients():
    raw = os.getenv('SMS_FALLBACK_RECIPIENTS', '')
    out = []
    for part in re.split(r'[,;]', raw):
        n = normalize_number(part)
        if n and n not in out:
            out.append(n)
    return out


def fallback_types():
    raw = os.getenv('SMS_FALLBACK_TYPES', ','.join(SMS_ALERT_TYPES))
    return {t.strip().upper() for t in raw.split(',') if t.strip()}


# ---------- Firebase settings cache ----------

def save_settings_cache(settings: dict) -> None:
    if isinstance(settings, dict) and settings:
        current = read_json(SETTINGS_CACHE, {}) or {}
        if current.get('settings') != settings:
            write_json(SETTINGS_CACHE, {'settings': settings, 'saved_at': int(time.time() * 1000)})


def load_settings_cache() -> dict:
    data = read_json(SETTINGS_CACHE, {}) or {}
    s = data.get('settings')
    return s if isinstance(s, dict) else {}


# ---------- SMS outbox ----------

def queue_sms_alert(alert: dict) -> None:
    """Store a temperature alert for the GSM worker. Never raises."""
    try:
        if str(alert.get('type', '')) not in SMS_ALERT_TYPES:
            return
        if alert.get('sms') is False:      # rate-limited by the alarm logic
            return
        item_id = f"{int(time.time() * 1000)}-{uuid.uuid4().hex[:8]}"
        write_json(OUTBOX_DIR / f'{item_id}.json', {
            'id': item_id,
            'alert': alert,
            'queued_at': int(time.time() * 1000),
            'sent_to': [],
            'attempts': 0,
            'last_error': '',
        })
    except Exception as exc:
        print('WARNING: could not queue SMS alert:', exc, flush=True)


def outbox_items():
    try:
        return sorted(OUTBOX_DIR.glob('*.json'))
    except Exception:
        return []


def append_sms_log(entry: dict) -> None:
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        with SMS_LOG.open('a') as fh:
            fh.write(json.dumps(entry, separators=(',', ':')) + '\n')
        # Keep the log small on the SD card.
        if SMS_LOG.stat().st_size > 512 * 1024:
            lines = SMS_LOG.read_text().splitlines()[-1000:]
            write_json_lines(SMS_LOG, lines)
    except Exception as exc:
        print('WARNING: could not write SMS log:', exc, flush=True)


def write_json_lines(path: Path, lines) -> None:
    tmp = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    tmp.write_text('\n'.join(lines) + '\n')
    os.replace(tmp, path)


def recent_sms_log(limit=20):
    try:
        lines = SMS_LOG.read_text().splitlines()[-limit:]
        return [json.loads(l) for l in reversed(lines) if l.strip()]
    except Exception:
        return []
