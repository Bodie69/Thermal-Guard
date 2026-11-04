import re, time, threading
import serial

class SIM800L:
    def __init__(self, port='/dev/serial0', baud=9600, timeout=1.0):
        self.port = port
        self.baud = baud
        self.timeout = timeout
        self.ser = None
        self.lock = threading.RLock()
        self.last_error = None

    def open(self):
        with self.lock:
            if self.ser and self.ser.is_open:
                return True
            try:
                self.ser = serial.Serial(self.port, self.baud, timeout=self.timeout, write_timeout=3)
                time.sleep(0.5)
                self.ser.reset_input_buffer()
                if 'OK' in self.command('AT', wait=1.0):
                    self.last_error = None
                    return True
                # Close again so the next open() re-checks AT instead of
                # treating a silent modem as ready.
                self.last_error = 'SIM800L did not answer AT (check power and TX/RX wiring)'
                self.ser.close()
                return False
            except Exception as exc:
                self.last_error = str(exc)
                try:
                    if self.ser: self.ser.close()
                except Exception:
                    pass
                return False

    def close(self):
        with self.lock:
            try:
                if self.ser: self.ser.close()
            except Exception:
                pass

    def command(self, cmd, wait=1.0):
        with self.lock:
            if not self.ser or not self.ser.is_open:
                raise RuntimeError('SIM800L serial port is not open')
            self.ser.reset_input_buffer()
            self.ser.write((cmd+'\r').encode())
            self.ser.flush()
            deadline = time.time()+wait
            chunks=[]
            while time.time()<deadline:
                n=self.ser.in_waiting
                if n:
                    chunks.append(self.ser.read(n).decode(errors='ignore'))
                time.sleep(0.05)
            return ''.join(chunks)

    def registration(self):
        r = self.command('AT+CREG?', 1.0)
        m = re.search(r'\+CREG:\s*\d,([0-9])', r)
        code = int(m.group(1)) if m else -1
        return code in (1,5), code, r

    def signal(self):
        r = self.command('AT+CSQ', 1.0)
        m = re.search(r'\+CSQ:\s*(\d+),(\d+)', r)
        return (int(m.group(1)) if m else -1), r

    def operator(self):
        r = self.command('AT+COPS?', 1.0)
        m = re.search(r'\+COPS:.*?,"([^"]+)"', r)
        return m.group(1) if m else ''

    def send_sms(self, number, message):
        with self.lock:
            if not self.open():
                return False, self.last_error or 'modem open failed'
            try:
                self.command('AT+CMGF=1', 0.6)
                self.ser.reset_input_buffer()
                self.ser.write((f'AT+CMGS="{number}"\r').encode()); self.ser.flush()
                deadline=time.time()+5; buf=''
                while time.time()<deadline and '>' not in buf:
                    n=self.ser.in_waiting
                    if n: buf += self.ser.read(n).decode(errors='ignore')
                    time.sleep(0.05)
                if '>' not in buf:
                    return False, 'No SMS prompt from modem: '+buf[-120:]
                self.ser.write(message.encode(errors='ignore') + bytes([26])); self.ser.flush()
                deadline=time.time()+20; out=''
                while time.time()<deadline:
                    n=self.ser.in_waiting
                    if n:
                        out += self.ser.read(n).decode(errors='ignore')
                        if '+CMGS:' in out and 'OK' in out: return True, out
                        if '+CMS ERROR' in out or 'ERROR' in out: return False, out
                    time.sleep(0.1)
                return False, 'SMS send timeout: '+out[-200:]
            except Exception as exc:
                self.last_error=str(exc); return False, str(exc)
