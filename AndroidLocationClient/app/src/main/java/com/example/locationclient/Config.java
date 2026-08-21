package com.example.locationclient;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 客户端配置。
 *
 * 服务端地址的配置优先级:
 * 1. 外部存储 /sdcard/Download/location_client_config.txt
 *    文件内容格式(每行一个键值对):
 *      server_url=http://192.168.1.100:8080/location
 *      device_id=client_001
 * 2. 内部存储 /data/data/com.example.locationclient/files/client_config.txt
 *    (可通过 adb push 推送)
 * 3. 下面 DEFAULT_SERVER_URL 默认值
 *
 * 若以上都没有,使用默认值。建议修改默认值后重新编译,或使用配置文件。
 */
public class Config {

    /** 默认服务端上报地址:公网 kjzyyd.fucku.top (80端口由FRP映射到本地9178) */
    public static final String DEFAULT_SERVER_URL = "http://kjzyyd.fucku.top/location";
    /** 备用地址:如果80端口不通,尝试9178端口 */
    public static final String FALLBACK_SERVER_URL = "http://kjzyyd.fucku.top:9178/location";

    /** 静止模式上报间隔(毫秒):2 分钟。手机静止时用单次定位,不占用 GPS,系统可正常休眠。 */
    public static final long IDLE_REPORT_INTERVAL_MS = 2 * 60 * 1000L;

    /** 移动模式上报间隔(毫秒):5 秒。检测到移动后才连续定位。 */
    public static final long MOVING_REPORT_INTERVAL_MS = 5 * 1000L;

    /** 位移超过此距离(米)即立即上报 */
    public static final float MOVEMENT_THRESHOLD_METERS = 20.0f;

    /** 加速度(m/s²)超过该值判定为「移动」。用加速度判断极省电,不会阻止系统休眠。 */
    public static final float MOTION_DETECT_THRESHOLD = 1.2f;

    /** 停止移动后,再等待这么久才切回静止省电模式(毫秒) */
    public static final long IDLE_GRACE_MS = 60 * 1000L;

    /** 单次定位超时(毫秒) */
    public static final long LOCATION_TIMEOUT_MS = 20 * 1000L;

    /** 设备 ID(可在配置文件中覆盖) */
    public static final String DEFAULT_DEVICE_ID = "client_001";

    private static String cachedServerUrl = null;
    private static String cachedDeviceId = null;

    public static String getServerUrl(Context ctx) {
        loadConfig(ctx);
        return cachedServerUrl != null ? cachedServerUrl : DEFAULT_SERVER_URL;
    }

    public static String getDeviceId(Context ctx) {
        loadConfig(ctx);
        return cachedDeviceId != null ? cachedDeviceId : DEFAULT_DEVICE_ID;
    }

    private static synchronized void loadConfig(Context ctx) {
        if (cachedServerUrl != null) return;

        // 1. 外部存储 Download 目录
        File ext = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "location_client_config.txt");
        if (!ext.exists()) {
            // 2. 应用内部 files 目录
            ext = new File(ctx.getFilesDir(), "client_config.txt");
        }

        if (ext.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(ext))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int idx = line.indexOf('=');
                    if (idx <= 0) continue;
                    String key = line.substring(0, idx).trim();
                    String val = line.substring(idx + 1).trim();
                    if ("server_url".equalsIgnoreCase(key)) {
                        cachedServerUrl = val;
                    } else if ("device_id".equalsIgnoreCase(key)) {
                        cachedDeviceId = val;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** 重新加载配置(配置文件修改后调用) */
    public static synchronized void reload(Context ctx) {
        cachedServerUrl = null;
        cachedDeviceId = null;
        loadConfig(ctx);
    }
}
