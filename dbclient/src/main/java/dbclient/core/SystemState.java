package dbclient.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Central state management for XDJ-XZ monitoring
 */
public class SystemState {
    private static Map<String, Object> status = new HashMap<>();
    private static List<Map<String, Object>> playerStatus = new ArrayList<>();
    private static Map<String, Object> trackInfo = new HashMap<>();
    private static List<String> logs = new ArrayList<>();
    private static long lastUpdate = 0;
    
    public static synchronized void init() {
        status = new HashMap<>();
        status.put("online", false);
        status.put("devices", new ArrayList<>());
        status.put("dbserverPort", 0);
        
        // 初始化 trackInfo 为明确结构
        trackInfo = new HashMap<>();
        trackInfo.put("title", null);
        trackInfo.put("artist", null);
        trackInfo.put("album", null);
        trackInfo.put("duration", null);
        trackInfo.put("bpm", null);
        
        logs = new ArrayList<>();
        lastUpdate = System.currentTimeMillis();
    }
    
    public static synchronized void setOnline(boolean online) {
        status.put("online", online);
        addLog(online ? "系统上线" : "系统离线");
    }
    
    public static synchronized void setDbserverPort(int port) {
        status.put("dbserverPort", port);
        addLog("DB Server 端口: " + port);
    }
    
    public static synchronized void updatePlayerStatus(Map<String, Object> player) {
        boolean found = false;
        for (int i = 0; i < playerStatus.size(); i++) {
            Map<String, Object> p = playerStatus.get(i);
            if (p.get("player").equals(player.get("player"))) {
                playerStatus.set(i, player);
                found = true;
                break;
            }
        }
        if (!found) {
            playerStatus.add(player);
        }
        lastUpdate = System.currentTimeMillis();
    }
    
    public static synchronized void updateTrack(String title, String artist, String album, int duration, double bpm) {
        trackInfo.put("title", title);
        trackInfo.put("artist", artist);
        trackInfo.put("album", album);
        trackInfo.put("duration", duration);
        trackInfo.put("bpm", bpm);
        if (title != null) {
            addLog("曲目: " + title);
        }
    }
    
    public static synchronized void addDevice(String address, int number) {
        Map<String, Object> device = new HashMap<>();
        device.put("address", address);
        device.put("number", number);
        List devices = (List) status.get("devices");
        if (devices != null) {
            devices.add(device);
        }
    }
    
    public static synchronized void addLog(String message) {
        String entry = "[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + message;
        logs.add(0, entry);
        if (logs.size() > 50) logs.remove(logs.size() - 1);
    }
    
    // API methods
    public static synchronized Map<String, Object> getStatus() {
        Map<String, Object> result = new HashMap<>(status);
        result.put("lastUpdate", lastUpdate);
        result.put("uptime", System.currentTimeMillis() - lastUpdate);
        return result;
    }
    
    public static synchronized List<Map<String, Object>> getPlayerStatus() {
        return new ArrayList<>(playerStatus);
    }
    
    public static synchronized Map<String, Object> getTrackInfo() {
        return new HashMap<>(trackInfo);
    }
    
    public static synchronized List<String> getLogs() {
        return new ArrayList<>(logs);
    }
}
