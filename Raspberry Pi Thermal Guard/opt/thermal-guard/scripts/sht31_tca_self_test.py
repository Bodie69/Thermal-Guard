#!/usr/bin/env python3
import time
import board, busio, adafruit_tca9548a, adafruit_sht31d
from app import config

i2c=busio.I2C(board.SCL, board.SDA)
tca=adafruit_tca9548a.TCA9548A(i2c,address=config.TCA9548A_ADDRESS)
print(f'TCA9548A address: 0x{config.TCA9548A_ADDRESS:02X}')
for z in config.ZONES:
    print(f'\n{z.name} - channel {z.tca_channel}')
    try:
        s=adafruit_sht31d.SHT31D(tca[z.tca_channel])
        for i in range(3):
            print(f'{i+1}: {s.temperature:.2f} C, {s.relative_humidity:.2f}% RH')
            time.sleep(1)
    except Exception as exc:
        print('ERROR:',repr(exc))
