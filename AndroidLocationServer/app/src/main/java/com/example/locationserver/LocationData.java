package com.example.locationserver;

import android.location.Location;

/**
 * 接收到的客户端位置数据。
 */
public class LocationData {
    public final String deviceId;
    public final double latitude;
    public final double longitude;
    public final float accuracy;
    public final float speed;
    public final long timestamp;
    public final String time;
    public final long receivedAt;

    public LocationData(String deviceId, double latitude, double longitude,
                        float accuracy, float speed, long timestamp, String time) {
        this.deviceId = deviceId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.speed = speed;
        this.timestamp = timestamp;
        this.time = time;
        this.receivedAt = System.currentTimeMillis();
    }

    /** 转成 Android Location 对象,方便计算距离 */
    public Location toLocation() {
        Location l = new Location("client");
        l.setLatitude(latitude);
        l.setLongitude(longitude);
        l.setAccuracy(accuracy);
        l.setSpeed(speed);
        l.setTime(timestamp);
        return l;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US,
                "%s: %.6f, %.6f (±%.0fm) @ %s",
                deviceId, latitude, longitude, accuracy, time);
    }
}
