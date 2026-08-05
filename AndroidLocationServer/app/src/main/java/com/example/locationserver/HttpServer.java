package com.example.locationserver;

import android.util.Log;

import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

/**
 * 内嵌 HTTP 服务器，接收客户端上报的位置数据。
 * 接口:
 *   POST /location   body: JSON {device_id, latitude, longitude, accuracy, speed, timestamp, time}
 *   GET  /locations  返回最新位置 JSON
 *   GET  /           返回服务在线状态
 */
public class HttpServer extends NanoHTTPD {

    private static final String TAG = "HttpServer";

    public HttpServer(int port) { super(port); }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            if (Method.POST.equals(method) && "/location".equals(uri)) return handlePostLocation(session);
            if (Method.GET.equals(method) && "/locations".equals(uri)) return handleGetLocations();
            if (Method.OPTIONS.equals(method)) return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""));
            if (Method.GET.equals(method) && "/".equals(uri))
                return cors(newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"status\":\"ok\",\"service\":\"location_server\"}"));
            return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found"));
        } catch (Throwable e) {
            try { Log.e(TAG, "serve error", e); } catch (Throwable ignore) {}
            return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain", "Error: " + (e != null ? e.getMessage() : "unknown")));
        }
    }

    private Response handlePostLocation(IHTTPSession session) {
        try {
            java.util.Map<String, String> files = new java.util.HashMap<>();
            try { session.parseBody(files); } catch (Throwable ignore) {}
            String body = session.getQueryParameterString();
            if (body == null || body.isEmpty()) body = files.get("postData");
            if (body == null || body.isEmpty())
                return cors(newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        "application/json", "{\"error\":\"empty body\"}"));

            JSONObject json = new JSONObject(body);
            String deviceId = json.optString("device_id", "unknown");
            double lat = json.optDouble("latitude", 0);
            double lng = json.optDouble("longitude", 0);
            float acc = (float) json.optDouble("accuracy", 0);
            float speed = (float) json.optDouble("speed", 0);
            long ts = json.optLong("timestamp", System.currentTimeMillis());
            String time = json.optString("time", "");

            LocationData data = new LocationData(deviceId, lat, lng, acc, speed, ts, time);
            try { LocationStore.getInstance().update(data); } catch (Throwable ignore) {}
            try { Log.i(TAG, "Received: " + data); } catch (Throwable ignore) {}
            return cors(newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}"));
        } catch (Throwable t) {
            try { Log.e(TAG, "post error", t); } catch (Throwable ignore) {}
            return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"error\":\"" + (t != null ? t.getMessage().replace("\"", "'") : "unknown") + "\"}"));
        }
    }

    private Response handleGetLocations() {
        try {
            LocationData d = LocationStore.getInstance().getLatest();
            String resp;
            if (d == null) {
                resp = "{\"locations\":[]}";
            } else {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("device_id", d.deviceId);
                    obj.put("latitude", d.latitude);
                    obj.put("longitude", d.longitude);
                    obj.put("accuracy", d.accuracy);
                    obj.put("speed", d.speed);
                    obj.put("timestamp", d.timestamp);
                    obj.put("time", d.time);
                    obj.put("received_at", d.receivedAt);
                } catch (Throwable ignore) {}
                resp = "{\"locations\":[" + obj.toString() + "]}";
            }
            return cors(newFixedLengthResponse(Response.Status.OK, "application/json", resp));
        } catch (Throwable t) {
            return cors(newFixedLengthResponse(Response.Status.OK, "application/json", "{\"locations\":[]}"));
        }
    }

    private Response cors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return r;
    }
}