package com.example.locationserver;

import android.util.Log;

import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

/**
 * 内嵌 HTTP 服务器,接收客户端上报的位置数据。
 *
 * 接口:
 *   POST /location   body: JSON {device_id, latitude, longitude, accuracy, speed, timestamp, time}
 *   GET  /locations  返回最新位置 JSON(便于调试/外部访问)
 *   GET  /           返回服务在线状态
 */
public class HttpServer extends NanoHTTPD {

    private static final String TAG = "HttpServer";

    public HttpServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();

            if (Method.POST.equals(method) && "/location".equals(uri)) {
                return handlePostLocation(session);
            } else if (Method.GET.equals(method) && "/locations".equals(uri)) {
                return handleGetLocations();
            } else if (Method.GET.equals(method) && "/".equals(uri)) {
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"status\":\"ok\",\"service\":\"location_server\"}");
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        } catch (Exception e) {
            Log.e(TAG, "serve error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain", "Error: " + e.getMessage());
        }
    }

    private Response handlePostLocation(IHTTPSession session) throws Exception {
        // NanoHTTPD 要求读取 body 必须先解析参数
        session.parseBody(new java.util.HashMap<String, String>());
        String body = session.getQueryParameterString();
        if (body == null || body.isEmpty()) {
            // files map 里可能存有原始 body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            body = files.get("postData");
        }
        if (body == null || body.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                    "application/json", "{\"error\":\"empty body\"}");
        }

        JSONObject json = new JSONObject(body);
        String deviceId = json.optString("device_id", "unknown");
        double lat = json.optDouble("latitude", 0);
        double lng = json.optDouble("longitude", 0);
        float acc = (float) json.optDouble("accuracy", 0);
        float speed = (float) json.optDouble("speed", 0);
        long ts = json.optLong("timestamp", System.currentTimeMillis());
        String time = json.optString("time", "");

        LocationData data = new LocationData(deviceId, lat, lng, acc, speed, ts, time);
        LocationStore.getInstance().update(data);

        Log.i(TAG, "Received: " + data);

        return newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"status\":\"ok\"}");
    }

    private Response handleGetLocations() {
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
            } catch (Exception ignore) {
            }
            resp = "{\"locations\":[" + obj.toString() + "]}";
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp);
    }
}
