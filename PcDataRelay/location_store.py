"""
内存位置存储: device_id -> 最新位置记录
线程安全
"""

import threading
import time
from dataclasses import dataclass, asdict
from typing import Dict, List, Optional


@dataclass
class LocationRecord:
    device_id: str
    latitude: float
    longitude: float
    accuracy: float = 0.0
    provider: str = ""
    timestamp: float = 0.0   # epoch seconds
    time_str: str = ""


class LocationStore:
    def __init__(self):
        self._lock = threading.RLock()
        self._data: Dict[str, LocationRecord] = {}

    def upsert(self, rec: LocationRecord) -> None:
        with self._lock:
            self._data[rec.device_id] = rec

    def get(self, device_id: str) -> Optional[LocationRecord]:
        with self._lock:
            return self._data.get(device_id)

    def get_all(self) -> List[LocationRecord]:
        with self._lock:
            return list(self._data.values())

    def snapshot_dict(self) -> dict:
        with self._lock:
            return {k: asdict(v) for k, v in self._data.items()}

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def count(self) -> int:
        with self._lock:
            return len(self._data)

    def cleanup_older_than(self, seconds: float) -> int:
        """移除早于指定秒数的记录, 返回被删除数量"""
        cutoff = time.time() - seconds
        removed = 0
        with self._lock:
            for k in list(self._data.keys()):
                if self._data[k].timestamp and self._data[k].timestamp < cutoff:
                    del self._data[k]
                    removed += 1
        return removed
