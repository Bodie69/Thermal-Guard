#!/usr/bin/env python3
"""Queue a test SMS through the local outbox (works without internet).

Usage on the Pi:
  cd /opt/thermal-guard && venv/bin/python scripts/queue_test_sms.py 09171234567 [message]

The running thermal-guard-gsm service picks it up within a few seconds.
Check the result with:  curl -s localhost:8080/api/sms
"""
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app import offline_store as store  # noqa: E402


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    number = store.normalize_number(sys.argv[1])
    if not number:
        raise SystemExit(f'Invalid number: {sys.argv[1]} (use 09xxxxxxxxx or +639xxxxxxxxx)')
    message = ' '.join(sys.argv[2:]) or 'Thermal Guard test SMS (sent from the Pi, no internet needed)'
    item_id = f'{int(time.time() * 1000)}-test-{uuid.uuid4().hex[:6]}'
    store.write_json(store.OUTBOX_DIR / f'{item_id}.json', {
        'id': item_id, 'type': 'TEST', 'recipients': [number], 'message': message,
        'queued_at': int(time.time() * 1000), 'sent_to': [], 'attempts': 0, 'last_error': '',
    })
    print(f'Queued test SMS to {number} ({item_id})')


if __name__ == '__main__':
    main()
