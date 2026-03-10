package dbclient.websocket;

import dbclient.ai.AiAgentService;
import dbclient.config.UserSettingsStore;
import dbclient.sync.SyncOutputManager;
import com.google.gson.Gson;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import javax.servlet.http.*;
import java.io.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Unified Jetty Server for HTTP + WebSocket.
 */
public class JettyServer {
    // 这是后端总枢纽：处理HTTP接口、WebSocket广播、并驱动同步输出状态。
    
    private static final Gson gson = new Gson();
    private static Server server;
    private static final Set<Session> sessions = new CopyOnWriteArraySet<>();
    private static ScheduledExecutorService stateScheduler;
    private static Object playersStateCache;
    private static final SyncOutputManager syncOutputManager = SyncOutputManager.getInstance();
    private static final UserSettingsStore settingsStore = UserSettingsStore.getInstance();
    private static final AiAgentService aiAgentService = AiAgentService.getInstance();
    
    // 启动统一服务：加载配置并对外提供 8080 的 API + WS。
    public static void start(int port) throws Exception {
        syncOutputManager.applySettings();
        server = new Server(new java.net.InetSocketAddress("0.0.0.0", port));
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        
        // WebSocket at /ws
        context.addServlet(new ServletHolder(new WsServlet()), "/ws");
        System.out.println("[WS] WebSocket endpoint /ws registered");
        
        // HTTP + Static
        context.addServlet(new ServletHolder(new HttpServlet() {
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                String path = request.getPathInfo();
                if (path == null || path.equals("/")) path = "/index.html";
                
                response.setHeader("Access-Control-Allow-Origin", "*");
                
                // Static files
                if (path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css")) {
                    serveStatic(path, response);
                    return;
                }
                
                // API
                try {
                    Class<?> dmClass = Class.forName("djlink.DeviceManager");
                    Object dm = dmClass.getMethod("getInstance").invoke(null);
                    Map<String, Object> result = null;
                    
                    if (path.equals("/api/config")) {
                        result = settingsStore.getAll();
                    } else if (path.equals("/api/sync/state")) {
                        result = syncOutputManager.getStatus();
                    } else if (path.equals("/api/scan")) {
                        result = (Map<String, Object>) dmClass.getMethod("getScanStatus").invoke(dm);
                    } else if (path.equals("/api/scan/toggle")) {
                        boolean enable = true;
                        String enabledQ = request.getParameter("enabled");
                        if (enabledQ != null) {
                            enable = "1".equals(enabledQ) || "true".equalsIgnoreCase(enabledQ) || "on".equalsIgnoreCase(enabledQ);
                        } else {
                            Map<String, Object> st = (Map<String, Object>) dmClass.getMethod("getScanStatus").invoke(dm);
                            Object scanning = st.get("scanning");
                            enable = !(scanning instanceof Boolean && (Boolean) scanning);
                        }
                        result = (Map<String, Object>) dmClass.getMethod("setScanning", boolean.class).invoke(dm, enable);
                    } else if (path.equals("/api/players/state") || path.equals("/api/devices")) {
                        result = (Map<String, Object>) dmClass.getMethod("getPlayersState").invoke(dm);
                        if (result != null && result.get("players") != null) {
                            updateState(result.get("players"));
                            syncOutputManager.onPlayersState(result);
                        }
                    } else if (path.equals("/api/mixer/state")) {
                        result = (Map<String, Object>) dmClass.getMethod("getMixerState").invoke(dm);
                    } else if (path.equals("/api/triggers/events") || path.equals("/api/players/events")) {
                        result = (Map<String, Object>) dmClass.getMethod("getTriggerEvents").invoke(dm);
                    } else if (path.equals("/api/djlink/track") || path.equals("/api/players/track")) {
                        result = (Map<String, Object>) dmClass.getMethod("getTrackInfo").invoke(dm);
                    } else if (path.equals("/api/djlink/sections") || path.equals("/api/players/sections")) {
                        result = (Map<String, Object>) dmClass.getMethod("getSections").invoke(dm);
                    } else if (path.equals("/api/djlink/beatgrid") || path.equals("/api/players/beatgrid")) {
                        result = (Map<String, Object>) dmClass.getMethod("getBeatGrid").invoke(dm);
                    } else if (path.equals("/api/djlink/cues") || path.equals("/api/players/cues")) {
                        result = (Map<String, Object>) dmClass.getMethod("getCuePoints").invoke(dm);
                    } else if (path.equals("/api/djlink/waveform") || path.equals("/api/players/waveform")) {
                        result = (Map<String, Object>) dmClass.getMethod("getWaveform").invoke(dm);
                    } else if (path.equals("/api/ai/players")) {
                        result = (Map<String, Object>) dmClass.getMethod("getAiPlayers").invoke(dm);
                    } else if (path.equals("/api/djlink/artwork") || path.equals("/api/players/artwork")) {
                        // Binary artwork endpoint: /api/djlink/artwork?player=1
                        try {
                            String playerQ = request.getParameter("player");
                            int player = playerQ != null ? Integer.parseInt(playerQ) : 1;

                            Class<?> artFinderClass = Class.forName("org.deepsymmetry.beatlink.data.ArtFinder");
                            Object artFinder = artFinderClass.getMethod("getInstance").invoke(null);
                            Object art = artFinderClass.getMethod("getLatestArtFor", int.class).invoke(artFinder, player);
                            if (art == null) {
                                response.setStatus(404);
                                response.getWriter().print("no artwork");
                                return;
                            }

                            java.nio.ByteBuffer buf = (java.nio.ByteBuffer) art.getClass().getMethod("getRawBytes").invoke(art);
                            byte[] bytes = new byte[buf.remaining()];
                            buf.get(bytes);

                            String ct = "image/jpeg";
                            if (bytes.length > 8 && bytes[0] == (byte)0x89 && bytes[1] == 0x50) ct = "image/png";
                            response.setContentType(ct);
                            // Cache by artwork id in URL to avoid flicker/re-download
                            response.setHeader("Cache-Control", "public, max-age=86400, immutable");
                            response.getOutputStream().write(bytes);
                            return;
                        } catch (Exception e) {
                            response.setStatus(404);
                            response.getWriter().print("no artwork");
                            return;
                        }
                    } else {
                        serveStatic(path, response);
                        return;
                    }
                    
                    response.setContentType("application/json");
                    response.getWriter().print(gson.toJson(result));
                } catch (Exception e) {
                    response.setContentType("application/json");
                    response.getWriter().print(gson.toJson(Map.of("error", e.getMessage())));
                }
            }
            
            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                String path = request.getPathInfo();
                if (path == null) path = "/";
                response.setHeader("Access-Control-Allow-Origin", "*");
                response.setContentType("application/json");

                String body = new String(request.getInputStream().readAllBytes());
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = body == null || body.isBlank() ? new ConcurrentHashMap<>() : gson.fromJson(body, Map.class);
                if (payload == null) payload = new ConcurrentHashMap<>();

                try {
                    if (path.equals("/api/config")) {
                        settingsStore.patch(payload);
                        syncOutputManager.applySettings();
                        response.getWriter().print(gson.toJson(Map.of("ok", true, "settings", settingsStore.getAll())));
                        return;
                    }
                    if (path.equals("/api/ai/command")) {
                        String prompt = String.valueOf(payload.getOrDefault("prompt", ""));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> trackMeta = payload.get("trackMeta") instanceof Map ? (Map<String, Object>) payload.get("trackMeta") : Map.of();
                        Map<String, Object> out = aiAgentService.createAndPersistRule(prompt, trackMeta);
                        response.getWriter().print(gson.toJson(out));
                        return;
                    }
                    response.setStatus(404);
                    response.getWriter().print(gson.toJson(Map.of("error", "unknown endpoint")));
                } catch (Exception e) {
                    response.setStatus(500);
                    response.getWriter().print(gson.toJson(Map.of("error", e.getMessage())));
                }
            }

            private void serveStatic(String path, HttpServletResponse response) throws IOException {
                if (path.startsWith("/")) path = path.substring(1);
                InputStream is = getClass().getClassLoader().getResourceAsStream("web/" + path);
                if (is == null) is = getClass().getClassLoader().getResourceAsStream("web/index.html");
                if (is == null) {
                    response.setStatus(404);
                    return;
                }
                String ct = "text/plain";
                if (path.endsWith(".html")) ct = "text/html;charset=utf-8";
                else if (path.endsWith(".js")) ct = "application/javascript;charset=utf-8";
                else if (path.endsWith(".css")) ct = "text/css;charset=utf-8";
                response.setContentType(ct);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                response.getOutputStream().write(bos.toByteArray());
            }
        }), "/*");
        
        server.start();
        System.out.println("Server started on port " + port);
    }
    
    public static void startStateBroadcast() {
        if (stateScheduler != null) return;
        stateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-state-push");
            t.setDaemon(true);
            return t;
        });
        stateScheduler.scheduleAtFixedRate(() -> {
            // Always fetch fresh data from DeviceManager
            try {
                Class<?> dmClass = Class.forName("djlink.DeviceManager");
                Object dm = dmClass.getMethod("getInstance").invoke(null);
                Object state = dmClass.getMethod("getPlayersState").invoke(dm);
                Object players = state != null ? ((Map<?,?>)state).get("players") : null;
                if (players != null) {
                    playersStateCache = players;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mapState = (Map<String, Object>) state;
                    syncOutputManager.onPlayersState(mapState);
                }
            } catch (Exception e) {
                // Ignore
            }
            
            if (!sessions.isEmpty()) pushState();
        }, 100, 100, TimeUnit.MILLISECONDS);
        System.out.println("[WS] STATE broadcast started (100ms)");
    }
    
    public static void updateState(Object players) { playersStateCache = players; }
    
    // 语义事件入口：收到 beat/section/track 事件后，同时推送给前端和输出引擎。
    public static void pushEvent(String type, Integer player, Map<String, Object> data) {
        syncOutputManager.onSemanticEvent(type, player, data);
        Map<String, Object> event = new ConcurrentHashMap<>();
        event.put("type", "EVENT");
        event.put("event", type);
        event.put("player", player);
        event.put("time", System.currentTimeMillis() / 1000);
        event.put("data", data != null ? data : new ConcurrentHashMap<>());
        broadcast(gson.toJson(event));
    }
    
    private static void pushState() {
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("type", "STATE");
            msg.put("time", System.currentTimeMillis() / 1000);
            msg.put("players", playersStateCache);
            msg.put("sync", syncOutputManager.getStatus());
            broadcast(gson.toJson(msg));
        } catch (Exception e) {}
    }
    
    private static void broadcast(String msg) {
        for (Session s : sessions) {
            try { if (s.isOpen()) s.getRemote().sendString(msg); } catch (IOException e) {}
        }
    }
    
    public static class WsServlet extends WebSocketServlet {
        public void configure(WebSocketServletFactory f) {
            f.getPolicy().setIdleTimeout(300000);
            f.setCreator((req, resp) -> new WsEndpoint());
        }
    }
    
    // FIX: Implement WebSocketListener interface
    public static class WsEndpoint implements WebSocketListener {
        private Session session;

        @Override
        public void onWebSocketConnect(Session s) {
            this.session = s;
            sessions.add(s);
            System.out.println("[WS] >>> CLIENT_CONNECTED " + s.getRemoteAddress() + " (total:" + sessions.size() + ")");
            if (playersStateCache != null) pushState();
        }
        
        @Override
        public void onWebSocketText(String message) {
            System.out.println("[WS] <<< " + message);
        }
        
        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            if (this.session != null) sessions.remove(this.session);
            System.out.println("[WS] >>> CLIENT_DISCONNECTED (total:" + sessions.size() + ")");
        }
        
        @Override
        public void onWebSocketError(Throwable cause) {
            System.out.println("[WS] ERROR: " + cause.getMessage());
        }
        
        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {
            // Not used
        }
    }
    
    public static void join() throws Exception {
        if (server != null) server.join();
    }
}
