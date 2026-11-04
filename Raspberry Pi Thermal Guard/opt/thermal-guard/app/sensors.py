import math
import statistics
import threading
import time
from dataclasses import dataclass, asdict
from typing import Optional

from . import config


@dataclass
class Reading:
    id: str
    name: str
    temperature: Optional[float]
    humidity: Optional[float]
    quality: str
    error: Optional[str]
    consecutive_errors: int
    total_errors: int
    reconnects: int
    last_seen: Optional[float]
    last_error_at: Optional[float]

    def to_dict(self):
        return asdict(self)


class SensorManager:
    """Read real SHT31 measurements through the TCA9548A multiplexer.

    Accuracy/stability policy:
    - Read multiple REAL SHT31 samples per cycle and use their median.
    - Reject impossible/non-finite measurements instead of publishing fake values.
    - Keep the SHT31 heater disabled during normal ambient monitoring.
    - Apply only explicit calibration offsets from .env (defaults are 0.0).
    - A missing sensor is OFFLINE and is re-probed on later cycles so hot-plug works.
    """

    def __init__(self):
        self._lock = threading.RLock()
        self._i2c = None
        self._tca = None
        self._sensors = {}
        self._health = {
            z.id: {
                'consecutive_errors': 0,
                'total_errors': 0,
                'reconnects': 0,
                'last_seen': None,
                'last_error_at': None,
                'last_error': None,
            }
            for z in config.ZONES
        }
        self._init_bus()

    def _init_bus(self):
        import board
        import busio
        import adafruit_tca9548a
        # THERMAL_GUARD_SAFE_TCA_UNLOCK
        def _tg_safe_tca_unlock(channel):
            try:
                channel.tca.i2c.writeto(channel.tca.address, b'\x00')
            except OSError as exc:
                if getattr(exc, 'errno', None) not in (5, 121):
                    raise
            finally:
                channel.tca.i2c.unlock()
        adafruit_tca9548a.TCA9548A_Channel.unlock = _tg_safe_tca_unlock

        self._i2c = busio.I2C(board.SCL, board.SDA)
        self._tca = adafruit_tca9548a.TCA9548A(
            self._i2c,
            address=config.TCA9548A_ADDRESS,
        )

        # Do not probe SHT31 sensors during FastAPI import.
        # A disconnected/stuck I2C sensor must never prevent port 8080
        # and the kiosk from starting. Zones are probed lazily by read().
        for z in config.ZONES:
            self._sensors[z.id] = None


    def _init_zone(self, z):
        import adafruit_sht31d

        sensor = adafruit_sht31d.SHT31D(
            self._tca[z.tca_channel],
            address=config.SHT31_ADDRESS,
        )
        # Heater changes the measured temperature. Keep it OFF for ambient use.
        sensor.heater = False
        self._sensors[z.id] = sensor
        self._health[z.id]['reconnects'] += 1
        return sensor

    @staticmethod
    def _valid_temperature(value):
        return value is not None and math.isfinite(value) and -40.0 <= value <= 125.0

    @staticmethod
    def _valid_humidity(value):
        return value is not None and math.isfinite(value) and 0.0 <= value <= 100.0

    def _read_samples(self, sensor):
        temperatures = []
        humidities = []
        last_error = None

        for index in range(config.SHT31_SAMPLE_COUNT):
            try:
                # Values come directly from the physical SHT31.
                temperature = float(sensor.temperature)
                humidity = float(sensor.relative_humidity)

                if not self._valid_temperature(temperature):
                    raise RuntimeError(f'Invalid SHT31 temperature: {temperature}')
                if not self._valid_humidity(humidity):
                    raise RuntimeError(f'Invalid SHT31 humidity: {humidity}')

                temperatures.append(temperature)
                humidities.append(humidity)
            except Exception as exc:
                last_error = exc

            if index < config.SHT31_SAMPLE_COUNT - 1:
                time.sleep(config.SHT31_SAMPLE_DELAY_SECONDS)

        if not temperatures or not humidities:
            raise last_error or RuntimeError('SHT31 returned no valid samples')

        # Median is robust against an occasional noisy sample. It is still a
        # measured value; it does not invent a temperature/humidity reading.
        return statistics.median(temperatures), statistics.median(humidities)

    @staticmethod
    def _offsets(zone_id):
        return config.SENSOR_CALIBRATION.get(zone_id, (0.0, 0.0))

    def read(self, z):
        with self._lock:
            h = self._health[z.id]
            try:
                sensor = self._sensors.get(z.id)
                if sensor is None:
                    sensor = self._init_zone(z)

                temperature, humidity = self._read_samples(sensor)
                temp_offset, hum_offset = self._offsets(z.id)

                temperature = round(temperature + temp_offset, 2)
                humidity = round(max(0.0, min(100.0, humidity + hum_offset)), 2)

                now = time.time()
                h['consecutive_errors'] = 0
                h['last_seen'] = now
                h['last_error'] = None

                return Reading(
                    z.id,
                    z.name,
                    temperature,
                    humidity,
                    'GOOD',
                    None,
                    0,
                    h['total_errors'],
                    h['reconnects'],
                    now,
                    h['last_error_at'],
                )
            except Exception as exc:
                now = time.time()
                h['consecutive_errors'] += 1
                h['total_errors'] += 1
                h['last_error_at'] = now
                h['last_error'] = str(exc)

                # Re-probe this TCA channel on the next cycle. This lets a
                # sensor become ONLINE after it is physically connected later.
                self._sensors[z.id] = None
                quality = (
                    'OFFLINE'
                    if h['consecutive_errors'] >= config.OFFLINE_CONFIRM_ERRORS
                    else 'RETRY'
                )

                return Reading(
                    z.id,
                    z.name,
                    None,
                    None,
                    quality,
                    str(exc),
                    h['consecutive_errors'],
                    h['total_errors'],
                    h['reconnects'],
                    h['last_seen'],
                    h['last_error_at'],
                )

    def diagnostics(self):
        with self._lock:
            return {
                'mode': config.SENSOR_MODE,
                'tca9548a_address': hex(config.TCA9548A_ADDRESS),
                'sht31_address': hex(config.SHT31_ADDRESS),
                'sample_count': config.SHT31_SAMPLE_COUNT,
                'sample_delay_seconds': config.SHT31_SAMPLE_DELAY_SECONDS,
                'calibration': {
                    zone_id: {
                        'temperature_offset_c': offsets[0],
                        'humidity_offset_percent': offsets[1],
                    }
                    for zone_id, offsets in config.SENSOR_CALIBRATION.items()
                },
                'zones': {k: dict(v) for k, v in self._health.items()},
            }
