#!/usr/bin/env python3
from firebase_admin import db
from app.firebase_io import FirebaseIO
f=FirebaseIO()
print('READY:',f.ready)
print('ERROR:',f.last_error)
if f.ready:
    print('DATABASE:',__import__('app.config',fromlist=['']).FIREBASE_DATABASE_URL)
    print('SETTINGS:',db.reference('/thermal_guard/settings').get())
    print('RELAY:',db.reference('/thermal_guard/commands/relay/mode').get())
    live=db.reference('/thermal_guard/system/live').get()
    print('LIVE EXISTS:',live is not None)
    if isinstance(live,dict): print('LIVE LAST UPDATE:',live.get('last_update'))
