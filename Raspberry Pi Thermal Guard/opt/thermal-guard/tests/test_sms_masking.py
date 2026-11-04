"""/api/sms must never return a full phone number. Run from /opt/thermal-guard:
    python -m unittest discover -s tests -v
"""
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

try:
    from app.offline_store import mask_number, mask_phone_numbers
except ImportError as exc:  # pragma: no cover
    raise unittest.SkipTest(f'python-dotenv not installed: {exc}')

NUMBERS = ['+639171234567', '+639281112233', '+15551234567']

# Same shapes the GSM worker writes to gsm_status.json and sms_log.jsonl.
SAMPLE = {
    'gsm': {
        'ready': True, 'signal': 18, 'operator': 'Smart',
        'last_sms_recipient': '+639171234567',
        'error': 'No SMS prompt from modem: AT+CMGS="+639281112233"\r\nERROR',
        'timestamp': '2026-11-04T02:30:00+00:00',
    },
    'recent': [
        {'recipient': '+639171234567', 'type': 'CRITICAL_TEMP', 'status': 'SENT',
         'detail': '\r\n+CMGS: 12\r\n\r\nOK', 'eventKey': '1793759411000-ab12cd34',
         'epoch': 1793759411000},
        {'recipient': '+15551234567', 'type': 'TEST', 'status': 'FAILED',
         'detail': 'AT+CMGS="+15551234567"\r\n+CMS ERROR: 500', 'eventKey': 'req-1',
         'epoch': 1793759412000},
    ],
    'recipients': {'app_count': 2, 'fallback_count': 1, 'configured': True},
    'outbox_pending': 0,
}


class SmsMaskingTests(unittest.TestCase):
    def test_mask_format(self):
        self.assertEqual(mask_number('+639171234567'), '+63*******567')
        self.assertEqual(mask_number('+15551234567'), '+15******567')

    def test_no_full_number_anywhere(self):
        out = json.dumps(mask_phone_numbers(SAMPLE))
        for n in NUMBERS:
            self.assertNotIn(n, out)
            self.assertNotIn(n[1:], out)
        self.assertIn('+63*******567', out)
        self.assertIn('AT+CMGS=\\"+63*******233\\"', out)

    def test_other_data_unchanged(self):
        out = mask_phone_numbers(SAMPLE)
        self.assertEqual(out['recent'][0]['eventKey'], '1793759411000-ab12cd34')
        self.assertEqual(out['recent'][0]['epoch'], 1793759411000)
        self.assertEqual(out['recent'][0]['detail'], '\r\n+CMGS: 12\r\n\r\nOK')
        self.assertEqual(out['gsm']['timestamp'], '2026-11-04T02:30:00+00:00')
        self.assertEqual(out['recipients'], SAMPLE['recipients'])
        self.assertIs(out['gsm']['ready'], True)

    def test_input_not_modified(self):
        before = json.dumps(SAMPLE)
        mask_phone_numbers(SAMPLE)
        self.assertEqual(json.dumps(SAMPLE), before)


if __name__ == '__main__':
    unittest.main()
