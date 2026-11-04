#!/usr/bin/env python3
"""Grant the admin role to a Firebase Auth UID.

Usage (from /opt/thermal-guard):  python3 -m scripts.add_admin <uid>

Merges role='admin' and active=True into /thermal_guard/users/<uid>,
keeping any existing fields (name, fcm_tokens, ...).
"""
import sys

from app.firebase_io import FirebaseIO
from firebase_admin import db


def main():
    if len(sys.argv) != 2 or not sys.argv[1].strip():
        print('usage: python3 -m scripts.add_admin <uid>')
        return 2
    uid = sys.argv[1].strip()
    FirebaseIO()  # initializes firebase_admin with the configured database URL
    ref = db.reference(f'/thermal_guard/users/{uid}')
    before = ref.get()
    ref.update({'role': 'admin', 'active': True})
    after = ref.get() or {}
    print(f"{uid}: role={after.get('role')} active={after.get('active')}"
          f" ({'updated existing user' if before else 'created new user record'})")
    return 0


if __name__ == '__main__':
    sys.exit(main())
