"""
地理工具: 距离计算、格式化
"""

import math


def haversine_meters(lat1, lng1, lat2, lng2) -> float:
    """Haversine 公式, 计算两点间的地表距离(米)"""
    if None in (lat1, lng1, lat2, lng2):
        return -1.0
    R = 6371008.8
    rlat1, rlat2 = math.radians(lat1), math.radians(lat2)
    dlat = rlat2 - rlat1
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dlat / 2) ** 2 + math.cos(rlat1) * math.cos(rlat2) * math.sin(dlng / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def format_distance(meters: float) -> str:
    if meters < 0:
        return "未知"
    if meters >= 1000:
        return "%.2f km" % (meters / 1000)
    return "%.0f 米" % meters
