package com.example.locationserver;

import android.Manifest;
import android.annotation.SuppressLint;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.util.Date;

public class MainActivity extends AppCompatActivity {

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

    // 报警中心点(可为服务端本机位置或地图上点击的位置)
    private GeoPoint alertCenter = null;
    // 报警状态(防止重复报警)
    private boolean inAlertState = false;

    private final LocationStore.OnLocationListener locationListener = data ->
            mainHandler.post(() -> onClientLocation(data));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // osmdroid 配置
        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(getFilesDir());
        Configuration.getInstance().setOsmdroidTileCache(getCacheDir());

        setContentView(R.layout.activity_main);

        bindViews();
        setupMap();
        setupListeners();
        createAlertChannel();
        requestNotificationPermission();

        // 默认显示一个起始位置
        GeoPoint start = new GeoPoint(30.2741, 120.1551); // 杭州
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(start);

        // 自动启动 HTTP 服务端(实用:打开即可用)
        startServer();

        // 启动时请求定位权限,以便自动设置报警中心到本机位置
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQ_LOCATION);
        }

        // 延迟 2 秒后自动将报警中心设置为本机位置(等待权限授予及定位服务就绪)
        mainHandler.postDelayed(this::setCenterToMyLocation, 2000);
    }

    private void startServer() {
        if (serverRunning) return;
        Intent intent = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        serverRunning = true;
        updateServerUI();
        refreshServerAddress();
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
        // 优先使用国内可高速访问的瓦片源(高德地图),避免 OpenStreetMap 国内加载慢/失败
        try {
            org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase gaode =
                    new org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
                            "Gaode",
                            0, 19, 256, "",
                            new String[]{
                                    "https://webst01.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}",
                                    "https://webst02.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}",
                                    "https://webst03.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}",
                                    "https://webst04.is.autonavi.com/appmaptile?style=8&x={x}&y={y}&z={z}"
                            }) {
                        @Override
                        public String getTileURLString(long pMapTileIndex) {
                            int x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex);
                            int y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex);
                            int z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex);
                            return getBaseUrl()
                                    .replace("{x}", String.valueOf(x))
                                    .replace("{y}", String.valueOf(y))
                                    .replace("{z}", String.valueOf(z));
                        }
                    };
            mapView.setTileSource(gaode);
        } catch (Throwable ignore) {
            mapView.setTileSource(TileSourceFactory.MAPNIK);
        }
        mapView.setBuiltInZoomControls(true);
        mapView.setMultiTouchControls(true);
        mapView.setUseDataConnection(true);
    }

    private void setupListeners() {
        btnToggle.setOnClickListener(v -> toggleServer());
        btnSetCenterMe.setOnClickListener(v -> setCenterToMyLocation());

        // 阈值改变时实时更新地图上的报警圆
        etThreshold.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (alertCenter != null) {
                    updateThresholdCircle();
                    mapView.invalidate();
                }
            }
        });

        // 长按地图设置报警中心
        mapView.getOverlays().add(new org.osmdroid.views.overlay.MapEventsOverlay(
                new org.osmdroid.events.MapEventsReceiver() {
                    @Override
                    public boolean singleTapConfirmedHelper(GeoPoint p) {
                        setAlertCenter(p);
                        return true;
                    }

                    @Override
                    public boolean longPressHelper(GeoPoint p) {
                        setAlertCenter(p);
                        return true;
                    }
                }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        LocationStore.getInstance().addListener(locationListener);
        refreshServerAddress();
        // 恢复显示已有位置
        LocationData d = LocationStore.getInstance().getLatest();
        if (d != null) onClientLocation(d);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        LocationStore.getInstance().removeListener(locationListener);
    }

    // ----------------- 服务端开关 -----------------

    private void toggleServer() {
        if (serverRunning) {
            stopService(new Intent(this, ServerService.class));
            serverRunning = false;
            updateServerUI();
            Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show();
        } else {
            startServer();
            Toast.makeText(this, R.string.server_running, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateServerUI() {
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
        String ip = ServerService.getLocalIpAddress();
        if (ip == null) {
            tvServerAddress.setText("请连接 WiFi 网络(需与客户端在同一局域网)");
        } else {
            tvServerAddress.setText("服务地址:http://" + ip + ":" + ServerService.PORT
                    + "/location");
        }
    }

    // ----------------- 位置接收与显示 -----------------

    private void onClientLocation(LocationData data) {
        // 获取该设备的备注
        String note = LocationStore.getInstance().getNote(data.deviceId);

        // 客户端位置文本(显示备注、坐标、时间)
        StringBuilder info = new StringBuilder();
        if (!note.isEmpty()) {
            info.append("备注: ").append(note).append("\n");
        }
        info.append(String.format(java.util.Locale.US, "%s: %.6f, %.6f (±%.0fm)",
                data.deviceId, data.latitude, data.longitude, data.accuracy));
        info.append("\n时间: ").append(data.time);
        tvClientInfo.setText(info.toString());

        GeoPoint clientPoint = new GeoPoint(data.latitude, data.longitude);

        // 更新客户端标记(标题包含备注)
        if (clientMarker == null) {
            clientMarker = new Marker(mapView);
            clientMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mapView.getOverlays().add(clientMarker);
        }
        clientMarker.setPosition(clientPoint);
        clientMarker.setTitle((note.isEmpty() ? "" : note + " | ") + data.deviceId);
        clientMarker.setSnippet(data.time);

        // 移动地图视野到客户端
        mapView.getController().animateTo(clientPoint);

        mapView.invalidate();

        // 计算距离并检查报警
        checkDistanceAndAlert(clientPoint);
    }

    // ----------------- 备注编辑 -----------------

    /** 弹出对话框编辑指定设备的备注 */
    private void showNoteDialog(final String deviceId) {
        if (deviceId == null) {
            Toast.makeText(this, "暂无客户端,无法编辑备注", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText et = new EditText(this);
        et.setHint(R.string.edit_note_hint);
        String existing = LocationStore.getInstance().getNote(deviceId);
        if (!existing.isEmpty()) {
            et.setText(existing);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit_note)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String text = et.getText().toString().trim();
                    LocationStore.getInstance().setNote(deviceId, text);
                    // 刷新当前设备的 UI 显示
                    LocationData cur = LocationStore.getInstance().getDevice(deviceId);
                    if (cur != null) {
                        onClientLocation(cur);
                    }
                    Toast.makeText(this, "备注已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ----------------- 报警中心 -----------------

    @SuppressLint("MissingPermission")
    private void setCenterToMyLocation() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQ_LOCATION);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            Toast.makeText(this, "无法获取定位服务", Toast.LENGTH_SHORT).show();
            return;
        }
        Location best = null;
        for (String p : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            } catch (SecurityException ignore) {
            } catch (IllegalArgumentException ignore) {
            }
        }
        if (best == null) {
            Toast.makeText(this, "暂未获取到本机位置,请打开定位后重试",
                    Toast.LENGTH_LONG).show();
            return;
        }
        setAlertCenter(new GeoPoint(best.getLatitude(), best.getLongitude()));
    }

    private void setAlertCenter(GeoPoint p) {
        alertCenter = p;
        // 更新中心标记
        if (centerMarker == null) {
            centerMarker = new Marker(mapView);
            centerMarker.setTitle("报警中心");
            centerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mapView.getOverlays().add(centerMarker);
        }
        centerMarker.setPosition(p);

        // 更新阈值圆
        updateThresholdCircle();
        mapView.invalidate();

        Toast.makeText(this, "已设置报警中心: "
                + String.format(java.util.Locale.US, "%.6f, %.6f",
                p.getLatitude(), p.getLongitude()), Toast.LENGTH_SHORT).show();

        // 立即用最新客户端位置重新计算距离
        LocationData d = LocationStore.getInstance().getLatest();
        if (d != null) {
            checkDistanceAndAlert(new GeoPoint(d.latitude, d.longitude));
        } else {
            tvDistance.setText("报警中心已设置,等待客户端上报位置...");
        }
    }

    private double getThresholdMeters() {
        try {
            return Double.parseDouble(etThreshold.getText().toString().trim());
        } catch (Exception e) {
            return 500;
        }
    }

    private void updateThresholdCircle() {
        if (thresholdCircle != null) {
            mapView.getOverlays().remove(thresholdCircle);
            thresholdCircle = null;
        }
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

    // ----------------- 距离计算与报警 -----------------

    private void checkDistanceAndAlert(GeoPoint clientPoint) {
        if (alertCenter == null) {
            tvDistance.setText("提示: 点击地图或按\"以本机位置为报警中心\"设置报警中心");
            return;
        }

        float[] results = new float[1];
        Location.distanceBetween(
                alertCenter.getLatitude(), alertCenter.getLongitude(),
                clientPoint.getLatitude(), clientPoint.getLongitude(),
                results);
        double distance = results[0];

        String distStr;
        if (distance >= 1000) {
            distStr = String.format(java.util.Locale.US, "距离报警中心:%.2f km",
                    distance / 1000);
        } else {
            distStr = String.format(java.util.Locale.US, "距离报警中心:%.0f 米", distance);
        }
        tvDistance.setText(distStr);

        if (!cbAlertEnabled.isChecked()) return;

        double threshold = getThresholdMeters();
        double hysteresisExit = threshold * 1.5; // 离开 1.5 倍距离才解除报警状态

        if (distance <= threshold) {
            if (!inAlertState) {
                inAlertState = true;
                triggerAlert(distance);
            }
        } else if (distance > hysteresisExit) {
            inAlertState = false;
        }
    }

    private void triggerAlert(double distance) {
        String content = String.format(java.util.Locale.US,
                "客户端已进入报警范围!距离约 %.0f 米", distance);
        appendAlertLog(content);

        // 通知
        sendAlertNotification(content);

        // 震动
        vibrate();

        // 屏幕上的提示
        Toast.makeText(this, content, Toast.LENGTH_LONG).show();
    }

    private void appendAlertLog(String msg) {
        String time = (String) DateFormat.format("HH:mm:ss", new Date());
        String prev = tvAlertLog.getText().toString();
        // 保留最近 5 条
        String[] lines = prev.split("\n");
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(time).append("] ").append(msg);
        int keep = Math.min(lines.length, 4);
        for (int i = 0; i < keep; i++) {
            String l = lines[i].trim();
            if (!l.isEmpty()) {
                sb.append("\n").append(l);
            }
        }
        tvAlertLog.setText(sb.toString());
    }

    private void createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL, getString(R.string.alert_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("客户端进入报警范围时提醒");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setLightColor(Color.RED);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void sendAlertNotification(String content) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠ 距离报警")
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(ALERT_NOTIF_ID, builder.build());
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 500, 200, 500, 200, 500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Exception ignore) {
        }
    }

    // ----------------- 权限 -----------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFY);
            }
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setCenterToMyLocation();
            } else {
                Toast.makeText(this, "需要定位权限才能获取本机位置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ----------------- 菜单 -----------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_clear) {
            LocationStore.getInstance().clear();
            if (clientMarker != null) {
                mapView.getOverlays().remove(clientMarker);
                clientMarker = null;
            }
            tvClientInfo.setText(R.string.no_data);
            tvDistance.setText("");
            inAlertState = false;
            mapView.invalidate();
            return true;
        } else if (id == R.id.menu_center_me) {
            setCenterToMyLocation();
            return true;
        } else if (id == R.id.menu_toggle_server) {
            toggleServer();
            return true;
        } else if (id == R.id.menu_edit_note) {
            // 编辑当前客户端的备注
            LocationData d = LocationStore.getInstance().getLatest();
            if (d == null) {
                Toast.makeText(this, "暂无客户端,无法编辑备注", Toast.LENGTH_SHORT).show();
            } else {
                showNoteDialog(d.deviceId);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // 退到后台时保持服务运行(不退出)
        moveTaskToBack(true);
    }
}
