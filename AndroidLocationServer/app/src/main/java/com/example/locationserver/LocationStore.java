package com.example.locationserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 位置数据存储(单例),用于在 Service 和 Activity 之间共享最新位置。
 * 支持多设备同时存储,并可为每个设备附加备注。
 * 监听器会在新位置到达时被回调(回调线程为 HTTP 服务器的工作线程,
 * UI 更新需自行 post 到主线程)。
 */
public class LocationStore {

    public interface OnLocationListener {
        void onLocationUpdated(LocationData data);
    }

    private static LocationStore instance;

    // 多设备位置数据: deviceId -> LocationData
    private final Map<String, LocationData> dataMap = new ConcurrentHashMap<>();
    // 多设备备注: deviceId -> note
    private final Map<String, String> notesMap = new ConcurrentHashMap<>();
    // 最近一次更新的设备 id(getLatest 返回它的数据)
    private volatile String lastDeviceId = null;

    private final List<OnLocationListener> listeners = new CopyOnWriteArrayList<>();

    private LocationStore() {
    }

    public static synchronized LocationStore getInstance() {
        if (instance == null) {
            instance = new LocationStore();
        }
        return instance;
    }

    public void update(LocationData data) {
        if (data == null) return;
        dataMap.put(data.deviceId, data);
        lastDeviceId = data.deviceId;
        for (OnLocationListener l : listeners) {
            l.onLocationUpdated(data);
        }
    }

    /** 返回最近一次更新的设备位置 */
    public LocationData getLatest() {
        String id = lastDeviceId;
        if (id == null) return null;
        return dataMap.get(id);
    }

    /** 返回指定设备的位置数据 */
    public LocationData getDevice(String deviceId) {
        if (deviceId == null) return null;
        return dataMap.get(deviceId);
    }

    /** 返回所有已接收位置数据的设备列表 */
    public List<LocationData> getAllDevices() {
        return new ArrayList<>(dataMap.values());
    }

    public synchronized void clear() {
        dataMap.clear();
        notesMap.clear();
        lastDeviceId = null;
    }

    // ----------------- 备注相关 -----------------

    public void setNote(String deviceId, String note) {
        if (deviceId == null) return;
        if (note == null) note = "";
        notesMap.put(deviceId, note);
    }

    public String getNote(String deviceId) {
        if (deviceId == null) return "";
        return notesMap.getOrDefault(deviceId, "");
    }

    public Map<String, String> getAllNotes() {
        return new HashMap<>(notesMap);
    }

    public void addListener(OnLocationListener l) {
        listeners.add(l);
    }

    public void removeListener(OnLocationListener l) {
        listeners.remove(l);
    }
}
