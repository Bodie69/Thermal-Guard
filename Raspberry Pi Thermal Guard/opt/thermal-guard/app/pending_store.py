"""Disk-backed queue of Firebase events that could not be sent yet.

Alerts, system alerts and history produced while Firebase is unreachable are
kept here until they are delivered. Each event is one small JSON file, written
atomically, so the queue survives a backend restart, the sensor-stall watchdog
exit, a crash or a power cut, and is replayed in order afterwards.

Drop-in for the deque main.py used before: append((kind, payload, queued_at)),
[0], popleft(), len() and truthiness. Nothing is written while Firebase is
reachable - events only land here when they could not be delivered.
"""
import os
import re
import threading
from collections import deque
from pathlib import Path

from . import offline_store

_NAME_RE = re.compile(r'^(\d{12})-[0-9a-f]{8}\.json$')


class DurablePending:
    def __init__(self, directory, maxlen):
        self.dir = Path(directory)
        self.maxlen = max(1, int(maxlen))
        self._items = deque()          # (path or None, kind, payload, queued_at)
        self._lock = threading.RLock()
        self._seq = 0
        self._write_warned = False
        self.loaded = 0
        self.dropped = 0
        self._load()

    # ---------- startup ----------

    def _load(self):
        try:
            files = sorted(p for p in self.dir.glob('*.json') if _NAME_RE.match(p.name))
        except Exception as exc:
            print('[OFFLINE] cannot read pending queue:', exc, flush=True)
            return
        for path in files:
            self._seq = max(self._seq, int(_NAME_RE.match(path.name).group(1)))
            item = offline_store.read_json(path)
            if (not isinstance(item, dict) or not isinstance(item.get('kind'), str)
                    or 'payload' not in item):
                print('[OFFLINE] removing unreadable pending event', path.name, flush=True)
                self._unlink(path)
                continue
            self._items.append((path, item['kind'], item['payload'],
                                float(item.get('queued_at') or 0.0)))
        self.loaded = len(self._items)
        self._trim()
        if self._items:
            print(f'[OFFLINE] {len(self._items)} unsent event(s) restored from disk; '
                  'they will be sent when Firebase is reachable', flush=True)

    # ---------- deque interface ----------

    def append(self, item):
        kind, payload, queued_at = item
        with self._lock:
            self._seq += 1
            path = self.dir / f'{self._seq:012d}-{os.urandom(4).hex()}.json'
            try:
                offline_store.write_json(path, {'kind': kind, 'payload': payload,
                                                'queued_at': queued_at})
            except Exception as exc:
                # SD card full/read-only: still keep it in memory.
                if not self._write_warned:
                    print('[OFFLINE] WARNING: cannot save pending event to disk '
                          f'(kept in memory only): {exc}', flush=True)
                    self._write_warned = True
                path = None
            self._items.append((path, kind, payload, queued_at))
            self._trim()

    def __getitem__(self, index):
        with self._lock:
            _, kind, payload, queued_at = self._items[index]
            return kind, payload, queued_at

    def popleft(self):
        with self._lock:
            path, kind, payload, queued_at = self._items.popleft()
            self._unlink(path)
            return kind, payload, queued_at

    def __len__(self):
        with self._lock:
            return len(self._items)

    def __bool__(self):
        return len(self) > 0

    # ---------- helpers ----------

    def _trim(self):
        """Over the limit: drop the oldest history sample first, alerts last."""
        while len(self._items) > self.maxlen:
            victim = next((i for i, it in enumerate(self._items) if it[1] == 'history'), 0)
            path = self._items[victim][0]
            del self._items[victim]
            self._unlink(path)
            self.dropped += 1
            if self.dropped == 1 or self.dropped % 100 == 0:
                print(f'[OFFLINE] pending queue full ({self.maxlen}); dropped '
                      f'{self.dropped} oldest event(s), history first', flush=True)

    @staticmethod
    def _unlink(path):
        if path is None:
            return
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        except Exception as exc:
            print('[OFFLINE] could not delete sent event file:', exc, flush=True)
