package com.example.locationserver;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 位置数据存储(单例),用于在 Service 和 Activity 之间共享最新位置。
 * 监听器会在新位置到达时被回调(回调线程为 HTTP 服务器的工作线程,
 * UI 更新需自行 post 到主线程)。
 */
public class LocationStore {

    public interface OnLocationListener {
        void onLocationUpdated(LocationData data);
    }

    private static LocationStore instance;

    private LocationData latest;
    private final List<OnLocationListener> listeners = new CopyOnWriteArrayList<>();

    private LocationStore() {
    }

    public static synchronized LocationStore getInstance() {
        if (instance == null) {
            instance = new LocationStore();
        }
        return instance;
    }

    public synchronized void update(LocationData data) {
        this.latest = data;
        for (OnLocationListener l : listeners) {
            l.onLocationUpdated(data);
        }
    }

    public synchronized LocationData getLatest() {
        return latest;
    }

    public synchronized void clear() {
        latest = null;
    }

    public void addListener(OnLocationListener l) {
        listeners.add(l);
    }

    public void removeListener(OnLocationListener l) {
        listeners.remove(l);
    }
}
