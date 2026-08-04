package com.example.locationclient;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * 后台定位上报服务。
 *
 * 实现方式:前台服务 + Handler 循环,每 2 分钟获取一次位置并上报到服务端。
 * 使用低优先级通知通道(无声音、无弹窗),满足 Android 前台服务必须显示通知的系统要求,
 * 但对用户打扰最小。
 */
public class LocationService extends Service {

    public static final String ACTION_START = "com.example.locationclient.ACTION_START";
    public static final String ACTION_STOP = "com.example.locationclient.ACTION_STOP";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "location_service_channel";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private boolean waitingLocation = false;

    private final Runnable reportRunnable = new Runnable() {
        @Override
        public void run() {
            reportOnce();
            // 2 分钟后再执行一次
            handler.postDelayed(this, Config.REPORT_INTERVAL_MS);
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
        createNotificationChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ 需要显式传入前台服务类型
            try { startForeground(NOTIFICATION_ID, n, 0x10); } catch (Throwable t) {
                try { startForeground(NOTIFICATION_ID, n); } catch (Throwable t2) {}
            }
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 立即上报一次,然后启动周期循环
        handler.post(reportRunnable);
        // 服务被杀后自动重启
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null && waitingLocation) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignore) {
            }
        }
        super.onDestroy();
    }

    private void reportOnce() {
        if (!hasLocationPermission()) {
            // 无权限时跳过,等待下次循环
            return;
        }
        // 先尝试用最近一次已知位置快速上报
        Location last = getLastKnownLocation();
        if (last != null) {
            LocationReporter.report(this, last);
        }
        // 同时请求一次新鲜定位用于下一次上报
        requestSingleUpdate();
    }

    private void requestSingleUpdate() {
        if (locationManager == null || !hasLocationPermission()) return;
        waitingLocation = true;
        try {
            // 优先 GPS,其次网络
            boolean gpsEnabled = false, netEnabled = false;
            try {
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception ignore) {
            }
            try {
                netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignore) {
            }
            if (gpsEnabled) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                        locationListener, Looper.getMainLooper());
            }
            if (netEnabled) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                        locationListener, Looper.getMainLooper());
            }
            // 超时自动停止监听,避免长期挂起
            handler.postDelayed(() -> {
                if (waitingLocation) {
                    try {
                        locationManager.removeUpdates(locationListener);
                    } catch (SecurityException ignore) {
                    }
                    waitingLocation = false;
                }
            }, Config.LOCATION_TIMEOUT_MS);
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (waitingLocation) {
                LocationReporter.report(LocationService.this, location);
                try {
                    locationManager.removeUpdates(this);
                } catch (SecurityException ignore) {
                }
                waitingLocation = false;
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private Location getLastKnownLocation() {
        if (locationManager == null || !hasLocationPermission()) return null;
        Location best = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = locationManager.getLastKnownLocation(provider);
                if (l != null) {
                    if (best == null || l.getTime() > best.getTime()) {
                        best = l;
                    }
                }
            } catch (SecurityException ignore) {
            } catch (IllegalArgumentException ignore) {
                // 该 provider 不存在
            }
        }
        return best;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("系统服务运行中")
                .setContentText("正在保持连接")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSound(null)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
