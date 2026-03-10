package dbclient.web;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import dbclient.core.*;
import dbclient.websocket.EventPusher;
import java.util.Map;

/**
 * Minimal HTTP server for real-time XDJ-XZ monitoring
 */
public class WebServer {
    private final HttpServer server;
    private final int port;
    
    public WebServer(int port) throws IOException {
        this.port = port;
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        
        // Create executor
        server.setExecutor(Executors.newFixedThreadPool(4));
        
        // Setup routes - existing
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/player", new PlayerHandler());
        server.createContext("/api/track", new TrackHandler());
        server.createContext("/api/logs", new LogsHandler());
        
        // DJ Link API routes (using beat-link)
        server.createContext("/api/devices", new DjDevicesHandler());
        server.createContext("/api/djlink/players", new DjPlayersHandler());
        server.createContext("/api/djlink/beat", new DjBeatHandler());
        server.createContext("/api/djlink/track", new DjTrackHandler());
        server.createContext("/api/djlink/beatgrid", new DjBeatGridHandler());
        server.createContext("/api/djlink/cues", new DjCuesHandler());
        server.createContext("/api/djlink/waveform", new DjWaveformHandler());
        server.createContext("/api/djlink/phrase", new DjPhraseHandler());
        server.createContext("/api/djlink/sections", new DjSectionsHandler());
        server.createContext("/api/ai/players", new DjAiPlayersHandler());
        server.createContext("/api/players/state", new DjPlayersStateHandler());
        server.createContext("/api/triggers/events", new DjTriggerEventsHandler());
        
        // Static files
        server.createContext("/", new StaticHandler());
    }
    
    public void start() {
        server.start();
        System.out.println("🌐 Web UI 已启动");
        System.out.println("   监听地址: 0.0.0.0:" + port);
        System.out.println("   局域网访问: http://192.168.100.200:" + port);
    }
    
    public void stop() {
        server.stop(0);
    }
    
    // ==================== Helper: Convert raw BPM to display BPM ====================
    
    private static final int RAW_BPM_INVALID = 65535;
    private static final double RAW_PITCH_MULTIPLIER = 1048576.0;  // 1.0 = 0%
    
    private static Map<String, Object> convertBpm(Map<String, Object> status) {
        Map<String, Object> result = new LinkedHashMap<>(status);
        
        // Convert players array
        List<Map<String, Object>> players = (List<Map<String, Object>>) status.get("players");
        if (players != null) {
            List<Map<String, Object>> convertedPlayers = new ArrayList<>();
            for (Map<String, Object> p : players) {
                Map<String, Object> cp = new LinkedHashMap<>(p);
                
                // Convert raw BPM to display BPM, handle invalid value
                if (p.get("bpm") != null) {
                    int rawBpm = ((Number) p.get("bpm")).intValue();
                    if (rawBpm == RAW_BPM_INVALID) {
                        cp.put("bpm", null);  // Invalid BPM
                    } else {
                        cp.put("bpm", rawBpm / 100.0);
                    }
                }
                
                // Pitch is already converted to percentage in DeviceManager
                if (p.get("pitch") != null) {
                    cp.put("pitch", p.get("pitch"));
                }
                
                convertedPlayers.add(cp);
            }
            result.put("players", convertedPlayers);
        }
        
        // Convert global BPM
        if (status.get("bpm") != null) {
            int rawBpm = ((Number) status.get("bpm")).intValue();
            if (rawBpm == RAW_BPM_INVALID) {
                result.put("bpm", null);
            } else {
                result.put("bpm", rawBpm / 100.0);
            }
        }
        
        return result;
    }
    
    // ==================== DJ Link Handlers ====================
    
    static class DjDevicesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> status = (Map<String, Object>) dmClass.getMethod("getPlayerStatus").invoke(dm);
                // Convert raw BPM to display BPM
                sendJson(exchange, convertBpm(status));
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjPlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> status = (Map<String, Object>) dmClass.getMethod("getPlayerStatus").invoke(dm);
                sendJson(exchange, status);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjBeatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> status = (Map<String, Object>) dmClass.getMethod("getPlayerStatus").invoke(dm);
                sendJson(exchange, status);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjTrackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> track = (Map<String, Object>) dmClass.getMethod("getTrackInfo").invoke(dm);
                sendJson(exchange, track);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjBeatGridHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> beatgrid = (Map<String, Object>) dmClass.getMethod("getBeatGrid").invoke(dm);
                sendJson(exchange, beatgrid);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjCuesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> cues = (Map<String, Object>) dmClass.getMethod("getCuePoints").invoke(dm);
                sendJson(exchange, cues);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjWaveformHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> waveform = (Map<String, Object>) dmClass.getMethod("getWaveform").invoke(dm);
                sendJson(exchange, waveform);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjPhraseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> phrase = (Map<String, Object>) dmClass.getMethod("getPhrase").invoke(dm);
                sendJson(exchange, phrase);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjSectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> sections = (Map<String, Object>) dmClass.getMethod("getSections").invoke(dm);
                sendJson(exchange, sections);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjAiPlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> aiPlayers = (Map<String, Object>) dmClass.getMethod("getAiPlayers").invoke(dm);
                sendJson(exchange, aiPlayers);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjPlayersStateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> state = (Map<String, Object>) dmClass.getMethod("getPlayersState").invoke(dm);
                
                // Update WebSocket state cache for STATE broadcasts
                Object players = state.get("players");
                if (players != null) {
                    EventPusher.updateState(players);
                }
                
                sendJson(exchange, state);
            } catch (Exception e) {
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    static class DjTriggerEventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Map<String, Object> events = (Map<String, Object>) dmClass.getMethod("getTriggerEvents").invoke(dm);
                System.out.println("=== Trigger events: " + events);
                sendJson(exchange, events);
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(exchange, Map.of("error", e.getMessage()));
            }
        }
    }
    
    // ==================== Existing Handlers ====================
    
    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJson(exchange, SystemState.getStatus());
        }
    }
    
    static class PlayerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJson(exchange, SystemState.getPlayerStatus());
        }
    }
    
    static class TrackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJson(exchange, SystemState.getTrackInfo());
        }
    }
    
    static class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJson(exchange, SystemState.getLogs());
        }
    }
    
    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            
            String contentType = getContentType(path);
            byte[] content = getStaticContent(path);
            
            if (content != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, content.length);
                OutputStream os = exchange.getResponseBody();
                os.write(content);
                os.close();
            } else {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
            }
        }
        
        String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            return "text/plain";
        }
        
        byte[] getStaticContent(String path) {
            try {
                InputStream is = getClass().getResourceAsStream("/web" + path);
                if (is != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                    return bos.toByteArray();
                }
            } catch (Exception e) {}
            return null;
        }
    }
    
    static void sendJson(HttpExchange exchange, Object data) throws IOException {
        String json = toJson(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = json.getBytes("utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
    
    static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            Map<?, ?> map = (Map<?, ?>) obj;
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(e.getKey()).append("\":");
                sb.append(toJson(e.getValue()));
                i++;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) obj;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj instanceof Number) return obj.toString();
        if (obj instanceof Boolean) return obj.toString();
        return "\"" + obj.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int wsPort = args.length > 1 ? Integer.parseInt(args[1]) : port;
        
        System.out.println("Starting Web Server on port " + port + "...");
        
        // Initialize WebSocket server
        EventPusher.init();
        
        // Start device manager in background
        DeviceManager.start();
        
        // Register event listener for WebSocket push
        try {
            Class<?> teClass = Class.forName("djlink.trigger.TriggerEngine");
            Class<?> listenerClass = Class.forName("djlink.trigger.TriggerEngine$EventListener");
            java.lang.reflect.Method setListener = teClass.getMethod("setEventListener", listenerClass);
            
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[] { listenerClass },
                (proxy, method, margs) -> {
                    if ("onEvent".equals(method.getName()) && margs != null && margs.length > 0) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> event = (Map<String, Object>) margs[0];
                        String type = (String) event.get("type");
                        Integer player = (Integer) event.get("player");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) event.get("data");
                        EventPusher.pushEvent(type, player, data);
                    }
                    return null;
                }
            );
            setListener.invoke(null, listener);
            System.out.println("[WS] Event listener registered");
        } catch (Exception e) {
            System.out.println("[WS] Failed to register listener: " + e.getMessage());
        }
        
        // Start HTTP server
        WebServer web = new WebServer(port);
        web.start();
        
        // Start WebSocket server on port 8081 (separate from HTTP)
        EventPusher.startServer(8081);
        
        // Start STATE broadcast after a short delay
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (Exception e) {}
            EventPusher.startStateBroadcast();
        }).start();
        
        System.out.println("WebSocket: ws://192.168.100.200:" + wsPort + "/ws");
        System.out.println("Press Ctrl+C to stop");
    }
}
