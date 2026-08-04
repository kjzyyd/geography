package com.example.locationserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.text.format.Formatter;

import androidx.core.app.NotificationCompat;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * 前台服务:保持 HTTP 服务器在后台持续运行。
 * 当 Activity 退到后台时,服务端仍能接收客户端上报。
 */
public class ServerService extends Service {

    public static final int PORT = 8080;
    private static final int NOTIFICATION_ID = 2001;
    private static final String CHANNEL_ID = "http_server_channel";

    private HttpServer httpServer;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startHttpServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (httpServer != null) {
            try {
                httpServer.stop();
            } catch (Exception ignore) {
            }
            httpServer = null;
        }
        super.onDestroy();
    }

    private void startHttpServer() {
        if (httpServer != null) return;
        try {
            httpServer = new HttpServer(PORT);
            httpServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.http_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("位置服务端运行中");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundCompat() {
        String ip = getLocalIpAddress();
        String text = (ip == null ? "等待网络" : "http://" + ip + ":" + PORT + "/location");
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("位置监控服务端运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mapmode)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        // 不指定 foregroundServiceType,避免 Android 14 需要额外声明
        // FOREGROUND_SERVICE_DATA_SYNC 权限导致 SecurityException 闪退
        startForeground(NOTIFICATION_ID, n);
    }

    /** 获取本机局域网 IPv4 地址 */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(
                    NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && ip.indexOf(':') < 0) {
                            // IPv4
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** 兼容旧版本的 Formatter */
    @SuppressWarnings("deprecation")
    public static String formatIp(Context ctx, int ip) {
        return Formatter.formatIpAddress(ip);
    }
}
