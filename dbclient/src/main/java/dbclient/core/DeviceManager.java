package dbclient.core;

import dbclient.connection.DbConnection;
import dbclient.protocol.DbProtocol;
import dbclient.parser.MetadataParser;
import java.util.*;

/**
 * Device Manager - 设备与数据管理
 */
public class DeviceManager {
    private static volatile boolean running = false;
    private static final String DEFAULT_HOST = "192.168.100.132";
    private static final int MAIN_PORT = 12523;
    
    public static void start() {
        if (running) return;
        running = true;
        
        SystemState.init();
        SystemState.addLog("DeviceManager 启动");
        
        new Thread(() -> {
            int dbPort = 0;
            
            try {
                // Step 1: 连接主端口 12523
                SystemState.addLog("连接主端口: " + DEFAULT_HOST + ":" + MAIN_PORT);
                DbConnection conn = new DbConnection(DEFAULT_HOST, MAIN_PORT);
                conn.connect();
                SystemState.addLog("TCP 连接成功");
                
                SystemState.setOnline(true);
                SystemState.addDevice(DEFAULT_HOST, 1);
                
                // 12523 握手
                DbProtocol protocol = new DbProtocol(conn);
                SystemState.addLog("发送 GREETING (12523)...");
                
                boolean handshakeOk = protocol.handshake(3);
                
                if (handshakeOk) {
                    SystemState.addLog("握手成功 (12523)");
                } else {
                    SystemState.addLog("握手失败，使用默认端口");
                }
                
                // 使用默认端口 48304
                dbPort = 48304;
                SystemState.setDbserverPort(dbPort);
                SystemState.addLog("使用 DB 端口: " + dbPort);
                
                // 断开 12523
                conn.disconnect();
                SystemState.addLog("断开主端口连接");
                
                // Step 2: 连接 48304，尝试两种方案
                if (dbPort > 0) {
                    SystemState.addLog("=== 方案 A: GREETING -> SETUP -> TRACK ===");
                    tryProtocolA(DEFAULT_HOST, dbPort);
                    
                    SystemState.addLog("=== 方案 B: GREETING -> TRACK (跳过 SETUP) ===");
                    tryProtocolB(DEFAULT_HOST, dbPort);
                }
                
            } catch (Exception e) {
                SystemState.addLog("错误: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }
    
    // 方案 A: GREETING -> SETUP -> TRACK
    private static void tryProtocolA(String host, int port) {
        try {
            SystemState.addLog("连接 " + host + ":" + port);
            DbConnection dbConn = new DbConnection(host, port);
            dbConn.connect();
            SystemState.addLog("方案A: TCP 连接成功");
            
            DbProtocol dbProto = new DbProtocol(dbConn);
            
            // GREETING
            SystemState.addLog("方案A: 发送 GREETING...");
            byte[] greeting = dbProto.sendGreetingOnly();
            SystemState.addLog("方案A: GREETING sent: " + bytesToHex(greeting));
            
            // SETUP
            SystemState.addLog("方案A: 发送 SETUP...");
            byte[] setup = dbProto.sendSetupOnly(3);
            SystemState.addLog("方案A: SETUP sent: " + bytesToHex(setup));
            
            // TRACK
            SystemState.addLog("方案A: 发送 TRACK_REQUEST...");
            byte[] resp = dbProto.requestTrackMetadata(0x03010201, 131);
            SystemState.addLog("方案A: TRACK resp: " + bytesToHex(resp));
            
            dbConn.disconnect();
        } catch (Exception e) {
            SystemState.addLog("方案A 失败: " + e.getMessage());
        }
    }
    
    // 方案 B: GREETING -> TRACK (跳过 SETUP)
    private static void tryProtocolB(String host, int port) {
        try {
            SystemState.addLog("连接 " + host + ":" + port);
            DbConnection dbConn = new DbConnection(host, port);
            dbConn.connect();
            SystemState.addLog("方案B: TCP 连接成功");
            
            DbProtocol dbProto = new DbProtocol(dbConn);
            
            // 只做 GREETING
            SystemState.addLog("方案B: 发送 GREETING...");
            byte[] greeting = dbProto.sendGreetingOnly();
            SystemState.addLog("方案B: GREETING sent: " + bytesToHex(greeting));
            byte[] greetingResp = dbProto.getLastResponse();
            SystemState.addLog("方案B: GREETING recv: " + bytesToHex(greetingResp));
            
            // 直接发送 TRACK (跳过 SETUP)
            SystemState.addLog("方案B: 发送 TRACK_REQUEST (跳过 SETUP)...");
            byte[] resp = dbProto.requestTrackMetadata(0x03010201, 131);
            SystemState.addLog("方案B: TRACK resp: " + bytesToHex(resp));
            SystemState.addLog("方案B: TRACK resp len: " + (resp != null ? resp.length : 0));
            
            if (resp != null && resp.length > 0) {
                MetadataParser.TrackMetadata tm = MetadataParser.parse(resp);
                if (tm != null) {
                    SystemState.updateTrack(tm.title, tm.artist, tm.album, tm.duration, tm.bpm);
                    SystemState.addLog("方案B: 解析成功 - " + tm.title);
                }
            }
            
            dbConn.disconnect();
        } catch (Exception e) {
            SystemState.addLog("方案B 失败: " + e.getMessage());
        }
    }
    
    private static void updatePlayerStatus() {
        Map<String, Object> player = new HashMap<>();
        player.put("player", 1);
        player.put("playing", true);
        player.put("bpm", 130.0);
        player.put("master", true);
        player.put("currentTime", null);
        SystemState.updatePlayerStatus(player);
    }
    
    private static String bytesToHex(byte[] b) {
        if (b == null) return "null";
        String s = "";
        for (int i = 0; i < Math.min(b.length, 64); i++) {
            s += String.format("%02x ", b[i]);
        }
        return s;
    }
    
    public static void stop() {
        running = false;
    }
}
