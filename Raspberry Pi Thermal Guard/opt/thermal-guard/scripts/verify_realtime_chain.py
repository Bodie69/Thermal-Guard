#!/usr/bin/env python3
import json
import math
import sys
import urllib.request

from app.firebase_io import FirebaseIO
from firebase_admin import db

API_URL = 'http://127.0.0.1:8080/api/live'
ZONES = ('entrance', 'exit', 'front')


def fetch_pi():
    with urllib.request.urlopen(API_URL, timeout=5) as response:
        return json.loads(response.read().decode('utf-8'))


def same_number(a, b, tolerance=0.011):
    if a is None or b is None:
        return a is None and b is None
    try:
        return math.isclose(float(a), float(b), abs_tol=tolerance, rel_tol=0.0)
    except Exception:
        return False


def main():
    FirebaseIO()  # initializes firebase_admin with the configured database URL
    pi = fetch_pi()
    fb = db.reference('/thermal_guard/system/live').get() or {}

    print('Thermal Guard real-time chain verification')
    print('PI version      :', pi.get('version'))
    print('Firebase version:', fb.get('version'))
    print()

    all_ok = True
    for zone_id in ZONES:
        pz = (pi.get('zones') or {}).get(zone_id) or {}
        fz = (fb.get('zones') or {}).get(zone_id) or {}
        temp_ok = same_number(pz.get('temperature'), fz.get('temperature'))
        hum_ok = same_number(pz.get('humidity'), fz.get('humidity'))
        status_ok = pz.get('status') == fz.get('status')
        ok = temp_ok and hum_ok and status_ok
        all_ok = all_ok and ok
        print(
            f'{zone_id:8s} | '
            f'PI={pz.get("temperature")}/{pz.get("humidity")} {pz.get("status")} | '
            f'FB={fz.get("temperature")}/{fz.get("humidity")} {fz.get("status")} | '
            f'{"MATCH" if ok else "CHECK"}'
        )

    print()
    if all_ok:
        print('RESULT: MATCH - Raspberry Pi and Firebase are carrying the same sensor values.')
        return 0
    print('RESULT: CHECK - a new sensor cycle may have occurred between reads; run again once.')
    return 1


if __name__ == '__main__':
    sys.exit(main())
