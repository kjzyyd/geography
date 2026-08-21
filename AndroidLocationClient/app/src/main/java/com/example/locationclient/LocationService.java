package com.example.locationclient;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 后台定位上报服务(省电 & 不阻止休眠版本,纯系统 API、零第三方依赖)。
 *
 * 核心思路:用「加速度计」判断手机是否在移动,而不是一直占用 GPS。
 *  - 静止时:每 2 分钟做一次单次定位并上报,结束后立即释放 GPS,系统可正常进入休眠,几乎不耗电。
 *  - 检测到移动时:才开启连续定位,每 5 秒上报一次;位移 ≥ 20 米立即上报。
 *  - 停止移动 1 分钟后自动切回静止省电模式。
 *
 * 全程不持有 WakeLock,因此不会阻止系统休眠;GPS 只在确实需要时短时工作,耗电最小化。
 */
public class LocationService extends Service implements SensorEventListener {

    public static final String ACTION_START = "com.example.locationclient.ACTION_START";
    public static final String ACTION_STOP = "com.example.locationclient.ACTION_STOP";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "location_service_channel";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor accelerometer;

    // 移动判断
    private boolean motionDetected = false;
    private long lastMotionMs = 0L;
    private float gx, gy, gz; // 重力(低通滤波后)
    private Location lastReportedLocation = null;
    private Location lastKnownLocation = null; // 最近一次拿到的位置(用于周期上报)

    // 是否正在连续定位(仅在移动模式下为 true)
    private boolean trackingFast = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            runCycle();
            schedule();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        createNotificationChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            try { startForeground(NOTIFICATION_ID, n, 0x10); } catch (Throwable t) {
                try { startForeground(NOTIFICATION_ID, n); } catch (Throwable t2) {}
            }
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        // 启动加速度计监听(极低功耗,用于检测移动,不会阻止休眠)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.post(ticker);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopLocationTracking();
        super.onDestroy();
    }

    private void runCycle() {
        if (!hasLocationPermission()) {
            return;
        }
        if (motionDetected) {
            // 移动模式:连续定位,每 5 秒上报一次(有新鲜位置就用,否则用上次已知位置)
            ensureFastTracking();
            if (lastKnownLocation != null) {
                report(lastKnownLocation);
            }
        } else {
            // 静止模式:释放连续 GPS,先上报最近一次已知位置,再做一次单次定位
            stopLocationTracking();
            Location last = getLastKnownLocation();
            if (last != null) {
                report(last);
            }
            requestSingleUpdate();
        }
    }

    private void schedule() {
        handler.removeCallbacks(ticker);
        if (motionDetected) {
            handler.postDelayed(ticker, Config.MOVING_REPORT_INTERVAL_MS);
        } else {
            handler.postDelayed(ticker, Config.IDLE_REPORT_INTERVAL_MS);
        }
    }

    /**
     * 移动模式:开启连续定位,位移 ≥ 20 米立刻上报,否则等到 5 秒定时上报。
     */
    private void ensureFastTracking() {
        if (trackingFast) return; // 已在连续定位
        trackingFast = true;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        Config.MOVING_REPORT_INTERVAL_MS, 0f,
                        gpsListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        Config.MOVING_REPORT_INTERVAL_MS, 0f,
                        networkListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void stopLocationTracking() {
        if (!trackingFast) return;
        trackingFast = false;
        try {
            locationManager.removeUpdates(gpsListener);
            locationManager.removeUpdates(networkListener);
        } catch (SecurityException ignore) {
        }
    }

    /**
     * GPS 连续定位监听(移动模式)。
     * Google 的 GPS 大部分时间在移动模式下才真正需要;这里直接上报并按位移阈值立即上报。
     */
    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onFastLocation(location);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private final LocationListener networkListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onFastLocation(location);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private void onFastLocation(Location loc) {
        if (loc == null) return;
        lastKnownLocation = loc;
        if (lastReportedLocation != null
                && loc.distanceTo(lastReportedLocation) >= Config.MOVEMENT_THRESHOLD_METERS) {
            report(loc); // 位移 ≥ 20 米,立即上报
        }
    }

    private void requestSingleUpdate() {
        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (gpsEnabled) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                        singleListener, Looper.getMainLooper());
            }
            if (netEnabled) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                        singleListener, Looper.getMainLooper());
            }
            // 超时自动取消,避免长时间挂起
            handler.postDelayed(() -> {
                try {
                    locationManager.removeUpdates(singleListener);
                } catch (SecurityException ignore) {
                }
            }, Config.LOCATION_TIMEOUT_MS);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    /**
     * 静止模式单次定位监听:拿到一次位置后即上报并释放。
     */
    private final LocationListener singleListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;
            lastKnownLocation = location;
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignore) {
            }
            // 位移阈值:即使静止模式下,若相对上次超过 20 米也立即上报并标记移动
            if (lastReportedLocation != null
                    && location.distanceTo(lastReportedLocation) >= Config.MOVEMENT_THRESHOLD_METERS) {
                motionDetected = true;
                lastMotionMs = System.currentTimeMillis();
            }
            report(location);
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    // ---------- 加速度计:判断是否在移动(省电核心) ----------
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        // 低通滤波器求重力方向,得到“线性加速度”
        final float alpha = 0.8f;
        gx = alpha * gx + (1 - alpha) * event.values[0];
        gy = alpha * gy + (1 - alpha) * event.values[1];
        gz = alpha * gz + (1 - alpha) * event.values[2];
        float ax = event.values[0] - gx;
        float ay = event.values[1] - gy;
        float az = event.values[2] - gz;
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        long now = System.currentTimeMillis();
        if (mag > Config.MOTION_DETECT_THRESHOLD) {
            lastMotionMs = now;
            if (!motionDetected) {
                motionDetected = true;
                LogI("检测到移动,开启高频定位");
            }
        } else if (motionDetected
                && now - lastMotionMs > Config.IDLE_GRACE_MS) {
            // 已静止一段时间,切回省电模式
            motionDetected = false;
            stopLocationTracking();
            LogI("已静止,切回省电模式(2分钟一次)");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void report(Location loc) {
        lastReportedLocation = new Location(loc);
        LocationReporter.report(this, loc);
    }

    private Location getLastKnownLocation() {
        Location best = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            } catch (SecurityException ignore) {
            } catch (IllegalArgumentException ignore) {
            }
        }
        return best;
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // API < 23:权限在安装时已授予
            return true;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void LogI(String msg) {
        android.util.Log.i("LocationService", msg);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "定位服务", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("后台定位运行中");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("系统服务运行中")
                .setContentText("正在保持连接")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(Notification.PRIORITY_MIN)
                .setSound(null)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}