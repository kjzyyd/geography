package com.example.locationserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.format.Formatter;

import androidx.core.app.NotificationCompat;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * 服务：保持 HTTP 服务器 + 中转站拉取/上报在后台持续运行。
 */
public class ServerService extends Service {

    public static final int PORT = 8080;
    private static final int NOTIFICATION_ID = 2001;
    private static final String CHANNEL_ID = "http_server_channel";

    private HttpServer httpServer;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        try { createNotificationChannel(); } catch (Throwable ignore) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { startHttpServer(); } catch (Throwable t) { t.printStackTrace(); }
        try { startForegroundCompat(); } catch (Throwable t) { t.printStackTrace(); }
        try { RelayFetcher.getInstance().start(); } catch (Throwable t) { t.printStackTrace(); }
        try { RelayReporter.getInstance().start(this); } catch (Throwable t) { t.printStackTrace(); }
        try { return START_STICKY; } catch (Throwable ignore) {}
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { RelayReporter.getInstance().stop(); } catch (Throwable ignore) {}
        try { RelayFetcher.getInstance().stop(); } catch (Throwable ignore) {}
        try { if (httpServer != null) { httpServer.stop(); httpServer = null; } } catch (Throwable ignore) {}
        try { stopForeground(true); } catch (Throwable ignore) {}
        try { super.onDestroy(); } catch (Throwable ignore) {}
    }

    private void startHttpServer() {
        if (httpServer != null) return;
        try { httpServer = new HttpServer(PORT); httpServer.start(); } catch (Throwable t) { t.printStackTrace(); }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.http_channel),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("位置服务端运行中");
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(channel);
            } catch (Throwable ignore) {}
        }
    }

    private void startForegroundCompat() {
        try {
            String ip = getLocalIpAddress();
            String text = (ip == null ? "等待网络" : "http://" + ip + ":" + PORT + "/location");
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("位置监控服务端运行中")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_mapmode)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                try { startForeground(NOTIFICATION_ID, n, 0x01); } catch (Throwable t) {
                    try { startForeground(NOTIFICATION_ID, n); } catch (Throwable t2) {}
                }
            } else {
                try { startForeground(NOTIFICATION_ID, n); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) { t.printStackTrace(); }
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && ip.indexOf(':') < 0) return ip;
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    @SuppressWarnings("deprecation")
    public static String formatIp(Context ctx, int ip) {
        return Formatter.formatIpAddress(ip);
    }
}