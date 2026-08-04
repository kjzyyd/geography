package com.example.locationserver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 服务端位置上报器：持续获取自身 GPS 位置，POST 到电脑中转站。
 * device_id = "server_phone"，这样电脑中转站能同时看到客户端和服务端。
 */
public class RelayReporter {

    private static final String TAG = "RelayReporter";
    private static final String URL_PRIMARY = "http://kjzyyd.fucku.top/location";
    private static final String URL_FALLBACK = "http://kjzyyd.fucku.top:9178/location";
    private static final int REPORT_MS = 30_000;
    private static final int TIMEOUT = 8_000;

    private static RelayReporter instance;
    private volatile boolean running;
    private Context appContext;
    private LocationManager lm;
    private Thread reportThread;
    private String workingUrl;
    private volatile Location lastLocation;

    public static synchronized RelayReporter getInstance() {
        if (instance == null) instance = new RelayReporter();
        return instance;
    }

    @SuppressLint("MissingPermission")
    public void start(Context context) {
        if (running) return;
        appContext = context.getApplicationContext();
        running = true;
        try {
            lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15000, 10, gpsListener); } catch (Throwable ignore) {}
                try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000, 10, gpsListener); } catch (Throwable ignore) {}
                fetchLastKnown();
            }
        } catch (Throwable t) { Log.w(TAG, "定位启动失败: " + t.getMessage()); }

        reportThread = new Thread(this::reportLoop, "RelayReporter");
        reportThread.setDaemon(true);
        reportThread.start();
        Log.i(TAG, "已启动");
    }

    public void stop() {
        running = false;
        if (lm != null) { try { lm.removeUpdates(gpsListener); } catch (Throwable ignore) {} lm = null; }
        if (reportThread != null) { reportThread.interrupt(); reportThread = null; }
    }

    public Location getLastLocation() { return lastLocation; }

    private final LocationListener gpsListener = new LocationListener() {
        public void onLocationChanged(Location l) { lastLocation = l; }
        public void onStatusChanged(String p, int s, Bundle e) {}
        public void onProviderEnabled(String p) {}
        public void onProviderDisabled(String p) {}
    };

    @SuppressLint("MissingPermission")
    private void fetchLastKnown() {
        if (lm == null) return;
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignore) {}
        }
        if (best != null) lastLocation = best;
    }

    private void reportLoop() {
        try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
        while (running) {
            try {
                Location loc = lastLocation;
                if (loc != null) {
                    reportOnce(loc);
                } else {
                    fetchLastKnown();
                }
            } catch (Throwable t) { Log.w(TAG, "异常: " + t.getMessage()); }
            try { Thread.sleep(REPORT_MS); } catch (InterruptedException e) { break; }
        }
        Log.i(TAG, "已停止");
    }

    private void reportOnce(Location loc) {
        String u1 = workingUrl != null ? workingUrl : URL_PRIMARY;
        String u2 = u1.equals(URL_PRIMARY) ? URL_FALLBACK : URL_PRIMARY;
        if (tryReport(u1, loc)) return;
        tryReport(u2, loc);
    }

    private boolean tryReport(String urlString, Location loc) {
        HttpURLConnection conn = null;
        try {
            JSONObject json = new JSONObject();
            json.put("device_id", "server_phone");
            json.put("latitude", loc.getLatitude());
            json.put("longitude", loc.getLongitude());
            json.put("accuracy", loc.getAccuracy());
            json.put("speed", loc.getSpeed());
            json.put("timestamp", loc.getTime());
            json.put("time", formatTime(loc.getTime()));

            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); os.flush(); }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) { workingUrl = urlString; return true; }
            return false;
        } catch (Throwable t) { return false; }
        finally { if (conn != null) try { conn.disconnect(); } catch (Throwable ignore) {} }
    }

    private static String formatTime(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }
}