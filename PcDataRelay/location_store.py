"""
内存位置存储: device_id -> 最新位置记录
线程安全
"""

import json
import os
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
    speed: float = 0.0
    provider: str = ""
    timestamp: float = 0.0   # epoch seconds
    time_str: str = ""


class LocationStore:
    def __init__(self):
        self._lock = threading.RLock()
        self._data: Dict[str, LocationRecord] = {}
        # 设备备注: device_id -> 备注文本
        self._notes: Dict[str, str] = {}

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

    # ---------------- 备注相关 ----------------
    def set_note(self, device_id: str, note: str) -> None:
        """设置某个设备的备注文本"""
        with self._lock:
            note = (note or "").strip()
            if note:
                self._notes[device_id] = note
            else:
                # 空备注视为清除
                self._notes.pop(device_id, None)

    def get_note(self, device_id: str) -> str:
        with self._lock:
            return self._notes.get(device_id, "")

    def get_all_notes(self) -> Dict[str, str]:
        with self._lock:
            # 返回副本，避免外部修改内部数据
            return dict(self._notes)

    def save_notes(self, path: str) -> None:
        """把备注持久化到 JSON 文件"""
        with self._lock:
            data = dict(self._notes)
        try:
            parent = os.path.dirname(os.path.abspath(path))
            if parent and not os.path.isdir(parent):
                os.makedirs(parent, exist_ok=True)
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            os.replace(tmp, path)
        except Exception:
            # 持久化失败不应影响主流程
            pass

    def load_notes(self, path: str) -> None:
        """从 JSON 文件载入备注"""
        if not os.path.isfile(path):
            return
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f) or {}
        except Exception:
            return
        if not isinstance(data, dict):
            return
        with self._lock:
            self._notes = {str(k): str(v) for k, v in data.items()}

    def clear(self) -> None:
        # 注意: 只清空位置数据，保留备注
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
