package com.example.locationclient;

import android.content.Context;
import android.location.Location;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 位置上报工具:通过 HTTP POST 把位置 JSON 发送到服务端。
 * 在后台线程执行,不阻塞主线程。
 *
 * 上报数据格式:
 * {
 *   "device_id": "client_001",
 *   "latitude": 30.123456,
 *   "longitude": 120.123456,
 *   "accuracy": 5.0,
 *   "speed": 0.0,
 *   "timestamp": 1700000000000,
 *   "time": "2024-01-01T12:00:00Z"
 * }
 */
public class LocationReporter {

    private static final String TAG = "LocationReporter";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 10_000;

    public static void report(Context ctx, Location location) {
        if (location == null) return;
        final String serverUrl = Config.getServerUrl(ctx);
        final String deviceId = Config.getDeviceId(ctx);
        final double lat = location.getLatitude();
        final double lng = location.getLongitude();
        final float acc = location.getAccuracy();
        final float speed = location.getSpeed();
        final long ts = location.getTime();
        final String timeStr = formatTime(ts);

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject json = new JSONObject();
                json.put("device_id", deviceId);
                json.put("latitude", lat);
                json.put("longitude", lng);
                json.put("accuracy", acc);
                json.put("speed", speed);
                json.put("timestamp", ts);
                json.put("time", timeStr);

                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

                URL url = new URL(serverUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setDoOutput(true);
                conn.setDoInput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                    os.flush();
                }
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    Log.w(TAG, "Server responded HTTP " + code);
                }
            } catch (Exception e) {
                Log.w(TAG, "Report failed: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static String formatTime(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(ms));
    }
}
