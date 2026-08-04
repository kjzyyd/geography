package com.example.locationserver;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

public class MainActivity extends Activity {

    private static final int REQ_NOTIFY = 3001;
    private static final int REQ_LOCATION = 3002;
    private static final String ALERT_CHANNEL = "distance_alert_channel";
    private static final int ALERT_NOTIF_ID = 4001;

    private MapView mapView;
    private Marker clientMarker;
    private Marker centerMarker;
    private Polygon thresholdCircle;

    private TextView tvServerStatus, tvServerAddress, tvClientInfo, tvDistance, tvAlertLog;
    private Button btnToggle, btnSetCenterMe;
    private EditText etThreshold;
    private CheckBox cbAlertEnabled;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean serverRunning = false;
    private GeoPoint alertCenter = null;
    private boolean inAlertState = false;
    private boolean firstLocation = true;

    private final LocationStore.OnLocationListener locationListener =
            data -> mainHandler.post(() -> onClientLocation(data));

    // ==================== onCreate ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            runOnUiThread(() -> showCrashFallback(sw.toString()));
        });
        try {
            safeInit();
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            showCrashFallback(sw.toString());
        }
    }

    private void safeInit() {
        safeRun(() -> {
            Configuration.getInstance().setUserAgentValue(getPackageName());
            Configuration.getInstance().setOsmdroidBasePath(getFilesDir());
            Configuration.getInstance().setOsmdroidTileCache(getCacheDir());
        }, "osmdroid config");

        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            createFallbackLayout("布局加载失败: " + t.getMessage());
            return;
        }

        safeRun(this::bindViews, "bindViews");
        safeRun(this::setupMap, "setupMap");
        safeRun(this::setupListeners, "setupListeners");
        safeRun(this::createAlertChannel, "alertChannel");
        safeRun(this::requestNotificationPermission, "notifyPerm");

        if (mapView != null) safeRun(() -> {
            mapView.getController().setZoom(15.0);
            mapView.getController().setCenter(new GeoPoint(30.2741, 120.1551));
        }, "start point");

        safeRun(this::startServer, "startServer");
        safeRun(() -> {
            if (!hasLocationPermission()) requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }, "requestLocation");

        mainHandler.postDelayed(() -> safeRun(this::setCenterToMyLocation, "autoCenter"), 2000);
    }

    private void safeRun(Runnable r, String tag) {
        try { r.run(); } catch (Throwable t) {}
    }

    private void showCrashFallback(String stack) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFFFFF3E0);
            root.setPadding(16, 16, 16, 16);
            TextView t = new TextView(this);
            t.setText("应用发生错误，请截图发给开发者:\n\n" + stack);
            t.setTextSize(11);
            t.setTextColor(Color.BLACK);
            t.setGravity(Gravity.CENTER);
            root.addView(t);
            setContentView(root);
        } catch (Throwable ignore) {}
    }

    private void createFallbackLayout(String error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);
        TextView t = new TextView(this);
        t.setText("位置监控服务端 - 启动失败\n" + error);
        t.setTextSize(16);
        t.setTextColor(Color.RED);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        setContentView(root);
    }

    // ==================== 初始化 ====================

    private void startServer() {
        if (serverRunning) return;
        safeRun(() -> {
            startService(new Intent(this, ServerService.class));
            serverRunning = true;
            updateServerUI();
            refreshServerAddress();
        }, "startServer");
    }

    private void bindViews() {
        tvServerStatus = findViewById(R.id.tvServerStatus);
        tvServerAddress = findViewById(R.id.tvServerAddress);
        tvClientInfo = findViewById(R.id.tvClientInfo);
        tvDistance = findViewById(R.id.tvDistance);
        tvAlertLog = findViewById(R.id.tvAlertLog);
        btnToggle = findViewById(R.id.btnToggle);
        btnSetCenterMe = findViewById(R.id.btnSetCenterMe);
        etThreshold = findViewById(R.id.etThreshold);
        cbAlertEnabled = findViewById(R.id.cbAlertEnabled);
        mapView = findViewById(R.id.mapView);
    }

    private void setupMap() {
        if (mapView == null) return;
        try {
            org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase gaode =
                    new org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase("Gaode", 0, 19, 256, "",
                            new String[]{"https://webst01.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}",
                                    "https://webst02.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}",
                                    "https://webst03.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}",
                                    "https://webst04.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}"}) {
                        @Override
                        public String getTileURLString(long idx) {
                            return getBaseUrl().replace("{x}", String.valueOf(org.osmdroid.util.MapTileIndex.getX(idx)))
                                    .replace("{y}", String.valueOf(org.osmdroid.util.MapTileIndex.getY(idx)))
                                    .replace("{z}", String.valueOf(org.osmdroid.util.MapTileIndex.getZoom(idx)));
                        }
                    };
            mapView.setTileSource(gaode);
        } catch (Throwable ignore) {
            try { mapView.setTileSource(TileSourceFactory.MAPNIK); } catch (Throwable i2) {}
        }
        safeRun(() -> {
            mapView.setBuiltInZoomControls(true);
            mapView.setMultiTouchControls(true);
            mapView.setUseDataConnection(true);
        }, "map controls");
    }

    private void setupListeners() {
        if (btnToggle != null) btnToggle.setOnClickListener(v -> toggleServer());
        if (btnSetCenterMe != null) btnSetCenterMe.setOnClickListener(v -> setCenterToMyLocation());
        if (etThreshold != null) etThreshold.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                if (alertCenter != null && mapView != null) { updateThresholdCircle(); mapView.invalidate(); }
            }
        });

        // 用 OnTouchListener 代替 MapEventsOverlay，避免拦截滑动事件
        if (mapView != null) safeRun(() -> {
            mapView.setClickable(true);
            mapView.setOnTouchListener(new View.OnTouchListener() {
                private float downX, downY;
                private boolean moved;
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        downX = e.getX(); downY = e.getY(); moved = false;
                    } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        if (Math.abs(e.getX() - downX) > 15 || Math.abs(e.getY() - downY) > 15) moved = true;
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (!moved) {
                            try {
                                GeoPoint p = (GeoPoint) mapView.getProjection().fromPixels((int) e.getX(), (int) e.getY());
                                setAlertCenter(p);
                            } catch (Throwable ignore) {}
                        }
                    }
                    return false; // 不消费事件，让 MapView 正常滑动
                }
            });
        }, "touch listener");
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onResume() {
        super.onResume();
        safeRun(() -> { if (mapView != null) mapView.onResume(); }, "mapResume");
        LocationStore.getInstance().addListener(locationListener);
        refreshServerAddress();
        LocationData d = LocationStore.getInstance().getLatest();
        if (d != null) onClientLocation(d);
    }

    @Override
    protected void onPause() {
        super.onPause();
        safeRun(() -> { if (mapView != null) mapView.onPause(); }, "mapPause");
        LocationStore.getInstance().removeListener(locationListener);
    }

    // ==================== 服务端控制 ====================

    private void toggleServer() {
        if (serverRunning) {
            safeRun(() -> stopService(new Intent(this, ServerService.class)), "stopService");
            serverRunning = false;
            updateServerUI();
            Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show();
        } else {
            startServer();
            Toast.makeText(this, R.string.server_running, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateServerUI() {
        if (tvServerStatus == null || btnToggle == null) return;
        if (serverRunning) {
            tvServerStatus.setText(R.string.server_running);
            tvServerStatus.setTextColor(Color.parseColor("#2E7D32"));
            btnToggle.setText(R.string.stop_server);
        } else {
            tvServerStatus.setText(R.string.server_stopped);
            tvServerStatus.setTextColor(Color.parseColor("#C62828"));
            btnToggle.setText(R.string.start_server);
        }
    }

    private void refreshServerAddress() {
        if (tvServerAddress == null) return;
        safeRun(() -> {
            String ip = ServerService.getLocalIpAddress();
            tvServerAddress.setText(ip == null ? "请连接 WiFi 网络(需与客户端在同一局域网)"
                    : "服务地址:http://" + ip + ":" + ServerService.PORT + "/location");
        }, "refreshAddress");
    }

    // ==================== 位置显示 ====================

    private void onClientLocation(LocationData data) {
        if (data == null) return;
        String note = LocationStore.getInstance().getNote(data.deviceId);

        if (tvClientInfo != null) {
            StringBuilder sb = new StringBuilder();
            if (!note.isEmpty()) sb.append("备注: ").append(note).append("\n");
            sb.append(String.format(java.util.Locale.US, "%s: %.6f, %.6f (±%.0fm)",
                    data.deviceId, data.latitude, data.longitude, data.accuracy));
            sb.append("\n时间: ").append(data.time);
            tvClientInfo.setText(sb.toString());
        }

        if (mapView == null) return;
        GeoPoint clientPoint = new GeoPoint(data.latitude, data.longitude);
        if (clientMarker == null) {
            clientMarker = new Marker(mapView);
            clientMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mapView.getOverlays().add(clientMarker);
        }
        clientMarker.setPosition(clientPoint);
        clientMarker.setTitle((note.isEmpty() ? "" : note + " | ") + data.deviceId);
        clientMarker.setSnippet(data.time);

        // 只在首次收到位置时自动移动地图，之后不打断用户操作
        if (firstLocation) {
            firstLocation = false;
            safeRun(() -> mapView.getController().animateTo(clientPoint), "animateTo");
        }
        mapView.invalidate();
        checkDistanceAndAlert(clientPoint);
    }

    // ==================== 备注 ====================

    private void showNoteDialog(final String deviceId) {
        if (deviceId == null) { Toast.makeText(this, "暂无客户端,无法编辑备注", Toast.LENGTH_SHORT).show(); return; }
        safeRun(() -> {
            final EditText et = new EditText(this);
            et.setHint(R.string.edit_note_hint);
            String existing = LocationStore.getInstance().getNote(deviceId);
            if (!existing.isEmpty()) et.setText(existing);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.edit_note).setView(et)
                    .setPositiveButton("确定", (d, w) -> {
                        String text = et.getText() != null ? et.getText().toString().trim() : "";
                        LocationStore.getInstance().setNote(deviceId, text);
                        LocationData cur = LocationStore.getInstance().getDevice(deviceId);
                        if (cur != null) onClientLocation(cur);
                        Toast.makeText(this, "备注已保存", Toast.LENGTH_SHORT).show();
                    }).setNegativeButton("取消", null).show();
        }, "noteDialog");
    }

    // ==================== 报警中心 ====================

    @SuppressLint("MissingPermission")
    private void setCenterToMyLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        safeRun(() -> {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) { Toast.makeText(this, "无法获取定位服务", Toast.LENGTH_SHORT).show(); return; }
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (Throwable ignore) {}
            }
            if (best == null) { Toast.makeText(this, "暂未获取到本机位置,请打开定位后重试", Toast.LENGTH_LONG).show(); return; }
            setAlertCenter(new GeoPoint(best.getLatitude(), best.getLongitude()));
        }, "setCenter");
    }

    private void setAlertCenter(GeoPoint p) {
        alertCenter = p;
        if (mapView != null) {
            if (centerMarker == null) {
                centerMarker = new Marker(mapView);
                centerMarker.setTitle("报警中心");
                centerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                mapView.getOverlays().add(centerMarker);
            }
            centerMarker.setPosition(p);
            updateThresholdCircle();
            mapView.invalidate();
        }
        Toast.makeText(this, "已设置报警中心: " + String.format(java.util.Locale.US, "%.6f, %.6f", p.getLatitude(), p.getLongitude()), Toast.LENGTH_SHORT).show();
        LocationData d = LocationStore.getInstance().getLatest();
        if (d != null) checkDistanceAndAlert(new GeoPoint(d.latitude, d.longitude));
        else if (tvDistance != null) tvDistance.setText("报警中心已设置,等待客户端上报位置...");
    }

    private double getThresholdMeters() {
        try { return Double.parseDouble(etThreshold.getText().toString().trim()); } catch (Exception e) { return 500; }
    }

    private void updateThresholdCircle() {
        if (mapView == null) return;
        if (thresholdCircle != null) { mapView.getOverlays().remove(thresholdCircle); thresholdCircle = null; }
        if (alertCenter == null) return;
        double radius = getThresholdMeters();
        if (radius <= 0) return;
        thresholdCircle = new Polygon(mapView);
        thresholdCircle.setPoints(Polygon.pointsAsCircle(alertCenter, radius));
        thresholdCircle.setFillColor(Color.argb(40, 33, 150, 243));
        thresholdCircle.setStrokeColor(Color.argb(180, 33, 150, 243));
        thresholdCircle.setStrokeWidth(2f);
        mapView.getOverlays().add(thresholdCircle);
    }

    // ==================== 距离报警 ====================

    private void checkDistanceAndAlert(GeoPoint clientPoint) {
        if (alertCenter == null) { if (tvDistance != null) tvDistance.setText("提示: 点击地图设置报警中心"); return; }
        float[] r = new float[1];
        Location.distanceBetween(alertCenter.getLatitude(), alertCenter.getLongitude(), clientPoint.getLatitude(), clientPoint.getLongitude(), r);
        double dist = r[0];
        if (tvDistance != null) tvDistance.setText(dist >= 1000 ? String.format(java.util.Locale.US, "距离报警中心:%.2f km", dist / 1000) : String.format(java.util.Locale.US, "距离报警中心:%.0f 米", dist));
        if (cbAlertEnabled == null || !cbAlertEnabled.isChecked()) return;
        double threshold = getThresholdMeters();
        if (dist <= threshold) { if (!inAlertState) { inAlertState = true; triggerAlert(dist); } }
        else if (dist > threshold * 1.5) inAlertState = false;
    }

    private void triggerAlert(double distance) {
        String content = String.format(java.util.Locale.US, "客户端已进入报警范围!距离约 %.0f 米", distance);
        appendAlertLog(content);
        sendAlertNotification(content);
        vibrate();
        Toast.makeText(this, content, Toast.LENGTH_LONG).show();
    }

    private void appendAlertLog(String msg) {
        if (tvAlertLog == null) return;
        safeRun(() -> {
            String time = (String) DateFormat.format("HH:mm:ss", new Date());
            String prev = tvAlertLog.getText() != null ? tvAlertLog.getText().toString() : "";
            String[] lines = prev.split("\n");
            StringBuilder sb = new StringBuilder("[" + time + "] " + msg);
            for (int i = 0; i < Math.min(lines.length, 4); i++) { if (!lines[i].trim().isEmpty()) sb.append("\n").append(lines[i].trim()); }
            tvAlertLog.setText(sb.toString());
        }, "alertLog");
    }

    private void createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeRun(() -> {
            NotificationChannel c = new NotificationChannel(ALERT_CHANNEL, getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("客户端进入报警范围时提醒");
            c.enableLights(true); c.enableVibration(true);
            c.setLightColor(Color.RED);
            c.setVibrationPattern(new long[]{0, 500, 200, 500});
            try { c.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()); } catch (Throwable ignore) {}
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }, "alertChannel");
    }

    private void sendAlertNotification(String content) {
        safeRun(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("距离报警").setContentText(content)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true).setContentIntent(pi);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ALERT_NOTIF_ID, b.build());
        }, "notification");
    }

    private void vibrate() {
        safeRun(() -> {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 500, 200, 500, 200, 500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        }, "vibrate");
    }

    // ==================== 权限 ====================

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] gr) {
        super.onRequestPermissionsResult(rc, p, gr);
        if (rc == REQ_LOCATION && gr.length > 0 && gr[0] == PackageManager.PERMISSION_GRANTED) setCenterToMyLocation();
        else if (rc == REQ_LOCATION) Toast.makeText(this, "需要定位权限才能获取本机位置", Toast.LENGTH_SHORT).show();
    }

    // ==================== 菜单 ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        try { getMenuInflater().inflate(R.menu.main, menu); } catch (Throwable ignore) {}
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_clear) {
            LocationStore.getInstance().clear();
            if (clientMarker != null && mapView != null) { mapView.getOverlays().remove(clientMarker); clientMarker = null; }
            if (tvClientInfo != null) tvClientInfo.setText(R.string.no_data);
            if (tvDistance != null) tvDistance.setText("");
            inAlertState = false;
            if (mapView != null) mapView.invalidate();
            return true;
        } else if (id == R.id.menu_center_me) { setCenterToMyLocation(); return true; }
        else if (id == R.id.menu_toggle_server) { toggleServer(); return true; }
        else if (id == R.id.menu_edit_note) {
            LocationData d = LocationStore.getInstance().getLatest();
            if (d == null) Toast.makeText(this, "暂无客户端,无法编辑备注", Toast.LENGTH_SHORT).show();
            else showNoteDialog(d.deviceId);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        try { moveTaskToBack(true); } catch (Throwable ignore) { super.onBackPressed(); }
    }
}