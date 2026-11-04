#!/usr/bin/env python3
from app import config
from app.sim800l import SIM800L
m=SIM800L(config.SIM800L_PORT,config.SIM800L_BAUD)
print('PORT:',config.SIM800L_PORT,'BAUD:',config.SIM800L_BAUD)
print('OPEN:',m.open())
if m.ser and m.ser.is_open:
    print('AT:',m.command('AT',1.0))
    print('CREG:',m.command('AT+CREG?',1.0))
    print('CSQ:',m.command('AT+CSQ',1.0))
    print('COPS:',m.command('AT+COPS?',1.0))
