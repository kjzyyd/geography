package com.example.locationserver;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

/**
 * 从公网中转站定期拉取位置数据。
 * 服务端手机每10秒 GET 中转站 /location，获取所有设备位置，更新到 LocationStore。
 */
public class RelayFetcher {

    private static final String TAG = "RelayFetcher";
    private static final String URL_PRIMARY = "http://kjzyyd.fucku.top/location";
    private static final String URL_FALLBACK = "http://kjzyyd.fucku.top:9178/location";
    private static final int INTERVAL_MS = 10_000;
    private static final int TIMEOUT = 5_000;

    private static RelayFetcher instance;
    private volatile boolean running;
    private Thread thread;
    private String workingUrl;

    public static synchronized RelayFetcher getInstance() {
        if (instance == null) instance = new RelayFetcher();
        return instance;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "RelayFetcher");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "已启动");
    }

    public void stop() {
        running = false;
        if (thread != null) { thread.interrupt(); thread = null; }
    }

    private void loop() {
        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        while (running) {
            try {
                fetchOnce();
            } catch (Throwable t) {
                Log.w(TAG, "异常: " + t.getMessage());
            }
            try { Thread.sleep(INTERVAL_MS); } catch (InterruptedException e) { break; }
        }
        Log.i(TAG, "已停止");
    }

    private void fetchOnce() {
        String u1 = workingUrl != null ? workingUrl : URL_PRIMARY;
        String u2 = u1.equals(URL_PRIMARY) ? URL_FALLBACK : URL_PRIMARY;
        if (tryFetch(u1)) return;
        tryFetch(u2);
    }

    private boolean tryFetch(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return false;

            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String body = sb.toString().trim();
            if (body.isEmpty() || body.equals("{}")) { workingUrl = urlString; return true; }

            JSONObject json = new JSONObject(body);
            int count = 0;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String did = keys.next();
                JSONObject o = json.optJSONObject(did);
                if (o == null) continue;
                double lat = o.optDouble("latitude", 0);
                double lng = o.optDouble("longitude", 0);
                if (lat == 0 && lng == 0) continue;
                float acc = (float) o.optDouble("accuracy", 0);
                float speed = (float) o.optDouble("speed", 0);
                double tsVal = o.optDouble("timestamp", 0);
                long ts = tsVal > 1e12 ? (long) tsVal : (long) (tsVal * 1000);
                if (ts <= 0) ts = System.currentTimeMillis();
                String timeStr = o.optString("time_str", o.optString("time", ""));
                LocationStore.getInstance().update(new LocationData(did, lat, lng, acc, speed, ts, timeStr));
                count++;
            }
            workingUrl = urlString;
            if (count > 0) Log.i(TAG, "拉取到 " + count + " 个设备");
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignore) {}
        }
    }
}