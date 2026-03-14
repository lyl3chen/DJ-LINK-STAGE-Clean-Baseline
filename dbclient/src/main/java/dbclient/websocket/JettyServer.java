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
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    
    private static final Gson gson = new Gson();
    private static Server server;
    private static final Set<Session> sessions = new CopyOnWriteArraySet<>();
    private static ScheduledExecutorService stateScheduler;
    private static Object playersStateCache;
    private static final SyncOutputManager syncOutputManager = SyncOutputManager.getInstance();
    private static final UserSettingsStore settingsStore = UserSettingsStore.getInstance();
    private static final AiAgentService aiAgentService = AiAgentService.getInstance();
    
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
                        result = withRuntime(settingsStore.getAll());
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
                        response.getWriter().print(gson.toJson(Map.of("ok", true, "settings", withRuntime(settingsStore.getAll()))));
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
                    if (path.equals("/api/ma2/test-bpm")) {
                        Object bpmObj = payload.get("bpm");
                        double bpm = bpmObj instanceof Number ? ((Number) bpmObj).doubleValue() : 120.0;
                        Map<String, Object> out = syncOutputManager.sendMa2TestBpm(bpm);
                        response.getWriter().print(gson.toJson(out));
                        return;
                    }
                    if (path.equals("/api/ma2/test-command")) {
                        String cmd = String.valueOf(payload.getOrDefault("command", "")).trim();
                        Map<String, Object> out = syncOutputManager.sendMa2TestCommand(cmd);
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

    private static Map<String, Object> withRuntime(Map<String, Object> settings) {
        Map<String, Object> out = new ConcurrentHashMap<>();
        if (settings != null) out.putAll(settings);
        Map<String, Object> rt = new ConcurrentHashMap<>();
        rt.put("audioDevices", listAudioDevices());
        rt.put("midiOutDevices", listMidiOutDevices());
        
        // Timecode source info
        Map<String, Object> sync = settings.get("sync") instanceof Map ? (Map<String, Object>) settings.get("sync") : Map.of();
        int timecodeSource = sync.get("timecodeSource") instanceof Number ? ((Number) sync.get("timecodeSource")).intValue() : 0;
        
        // 在线 CDJ 列表
        List<Map<String, Object>> onlinePlayers = new ArrayList<>();
        if (playersStateCache != null) {
            if (playersStateCache instanceof List) {
                for (Object o : (List<?>) playersStateCache) {
                    if (o instanceof Map) {
                        Map<String, Object> p = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(p.get("active"))) {
                            Map<String, Object> playerInfo = new ConcurrentHashMap<>();
                            playerInfo.put("number", p.get("number"));
                            playerInfo.put("name", p.get("name"));
                            playerInfo.put("playing", p.get("playing"));
                            onlinePlayers.add(playerInfo);
                        }
                    }
                }
            }
        }
        
        Map<String, Object> tcInfo = new ConcurrentHashMap<>();
        tcInfo.put("timecodeSource", timecodeSource);
        tcInfo.put("onlinePlayers", onlinePlayers);
        tcInfo.put("timecodeSourceValid", timecodeSource > 0 && onlinePlayers.stream().anyMatch(p -> p.get("number") instanceof Number && ((Number) p.get("number")).intValue() == timecodeSource));
        rt.put("timecodeSource", tcInfo);
        
        out.put("_runtime", rt);
        return out;
    }

    private static List<Map<String, Object>> listAudioDevices() {
        List<Map<String, Object>> list = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        // 1) Java Sound 可直接输出的设备
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        int idx = 1;
        for (Mixer.Info info : infos) {
            try {
                javax.sound.sampled.Mixer mixer = AudioSystem.getMixer(info);
                javax.sound.sampled.Line.Info[] src = mixer.getSourceLineInfo();
                boolean canOutput = false;
                for (javax.sound.sampled.Line.Info li : src) {
                    if (li.toString().contains("SourceDataLine")) { canOutput = true; break; }
                }
                if (!canOutput) continue;

                String name = info.getName();
                String hwId = extractHwId(name);
                String key = hwId != null ? hwId : name;
                if (key == null || key.isBlank() || seen.contains(key)) continue;
                seen.add(key);

                Map<String, Object> m = new ConcurrentHashMap<>();
                m.put("index", idx++);
                m.put("name", hwId != null ? hwId : name); // value 用于配置保存
                m.put("mixerName", name);
                m.put("vendor", info.getVendor());
                m.put("desc", info.getDescription());
                m.put("label", buildDeviceLabel(info));
                m.put("sourceLineCount", src.length);
                if (hwId != null) m.put("hwId", hwId);
                String probeText = (name + " " + info.getDescription()).toLowerCase();
                m.put("endpointType", classifyEndpointType(probeText));
                m.put("channelRole", classifyChannelRole(probeText));
                m.put("deviceOpenable", probeOpenable(hwId != null ? hwId : name));
                m.put("matchScore", scoreForLtcPreferred(probeText, probeOpenable(hwId != null ? hwId : name)));
                String w = occupancyWarning(hwId != null ? hwId : "");
                if (!w.isBlank()) m.put("warning", w);
                list.add(m);
            } catch (Exception ignored) {}
        }

        // 2) 补充 ALSA PCM 设备（确保如 hw:0,0 出现在下拉）
        for (Map<String, Object> pcm : listAlsaPcmDevices()) {
            String hw = String.valueOf(pcm.getOrDefault("hwId", ""));
            if (hw.isBlank() || seen.contains(hw)) continue;
            seen.add(hw);
            Map<String, Object> m = new ConcurrentHashMap<>();
            m.put("index", idx++);
            m.put("name", hw);
            m.put("hwId", hw);
            m.put("card", pcm.get("card"));
            m.put("device", pcm.get("device"));
            String label = String.valueOf(pcm.getOrDefault("label", hw));
            String desc = String.valueOf(pcm.getOrDefault("desc", "ALSA PCM"));
            String probeText = (label + " " + desc + " " + hw).toLowerCase();
            m.put("label", label);
            m.put("desc", desc);
            m.put("endpointType", classifyEndpointType(probeText));
            m.put("channelRole", classifyChannelRole(probeText));
            boolean openable = probeOpenable(hw);
            m.put("deviceOpenable", openable);
            m.put("matchScore", scoreForLtcPreferred(probeText, openable));
            String w = occupancyWarning(hw);
            if (!w.isBlank()) m.put("warning", w);
            list.add(m);
        }

        return list;
    }

    private static String buildDeviceLabel(Mixer.Info info) {
        String name = info.getName() == null ? "" : info.getName().trim();
        String desc = info.getDescription() == null ? "" : info.getDescription().trim();
        String low = name.toLowerCase();
        if (low.contains("default")) return "系统默认输出";
        String hw = extractHwId(name);
        if (!desc.isEmpty() && !desc.toLowerCase().contains("direct audio device")) {
            return hw == null ? desc : (desc + " [" + hw + "]");
        }
        int idx = name.indexOf("[");
        if (idx > 0) {
            String shortName = name.substring(0, idx).trim();
            if (!shortName.isEmpty()) return hw == null ? shortName : (shortName + " [" + hw + "]");
        }
        return name.isEmpty() ? "可用声卡" : name;
    }

    private static String extractHwId(String s) {
        if (s == null) return null;
        String low = s.toLowerCase();
        int p = low.indexOf("hw:");
        if (p < 0) return null;
        int end = p + 3;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (!(Character.isDigit(c) || c == ',')) break;
            end++;
        }
        String id = s.substring(p, end).toLowerCase();
        return id.matches("hw:\\d+,\\d+") ? id : null;
    }

    private static List<Map<String, Object>> listAlsaPcmDevices() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Path p = Path.of("/proc/asound/pcm");
            if (!Files.exists(p)) return out;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String line : lines) {
                // 例：00-00: ALC887-VD Analog : ALC887-VD Analog : playback 1 : capture 1
                if (line == null || line.isBlank() || !line.contains("playback")) continue;
                String[] parts = line.split(":", 3);
                if (parts.length < 2) continue;
                String id = parts[0].trim();
                String[] cd = id.split("-");
                if (cd.length != 2) continue;
                int card = Integer.parseInt(cd[0]);
                int dev = Integer.parseInt(cd[1]);
                String hw = "hw:" + card + "," + dev;
                String label = parts[1].trim();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("card", card);
                m.put("device", dev);
                m.put("hwId", hw);
                m.put("label", label + " [" + hw + "]");
                m.put("desc", line.trim());
                out.add(m);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String classifyEndpointType(String s) {
        String v = s == null ? "" : s.toLowerCase();
        if (v.contains("hdmi") || v.contains("display audio")) return "hdmi";
        if (v.contains("spdif") || v.contains("iec958") || v.contains("digital")) return "digital_spdif";
        if (v.contains("usb")) {
            if (v.contains("analog") || v.contains("headphone") || v.contains("line out") || v.contains("speaker") || v.contains("front")) return "usb_audio_analog_like";
            return "usb_audio_digital_like";
        }
        if (v.contains("analog") || v.contains("headphone") || v.contains("line out") || v.contains("speaker") || v.contains("front")) return "analog";
        return "unknown";
    }

    private static String classifyChannelRole(String s) {
        String v = s == null ? "" : s.toLowerCase();
        if (v.contains("headphone")) return "headphone";
        if (v.contains("front")) return "front";
        if (v.contains("line out")) return "lineout";
        if (v.contains("speaker")) return "speaker";
        if (v.contains("hdmi") || v.contains("spdif") || v.contains("digital")) return "digital";
        return "unknown";
    }

    private static int scoreForLtcPreferred(String text, boolean openable) {
        int score = openable ? 50 : 20;
        String ep = classifyEndpointType(text);
        if ("analog".equals(ep) || "usb_audio_analog_like".equals(ep)) score += 35;
        if ("hdmi".equals(ep) || "digital_spdif".equals(ep) || "usb_audio_digital_like".equals(ep)) score -= 20;
        String role = classifyChannelRole(text);
        if ("headphone".equals(role) || "front".equals(role) || "lineout".equals(role) || "speaker".equals(role)) score += 10;
        return Math.max(0, Math.min(100, score));
    }

    private static boolean probeOpenable(String idOrName) {
        try {
            String dev = idOrName == null ? "" : idOrName.trim().toLowerCase();
            if (dev.matches("hw:\\d+,\\d+")) {
                return occupancyWarning(dev).isBlank();
            }
        } catch (Exception ignored) {}
        return true;
    }

    private static String occupancyWarning(String hw) {
        try {
            String dev = hw == null ? "" : hw.trim().toLowerCase();
            if (!dev.matches("hw:\\d+,\\d+")) return "";
            String[] p = dev.substring(3).split(",");
            String target = "/dev/snd/pcmC" + Integer.parseInt(p[0]) + "D" + Integer.parseInt(p[1]) + "p";
            Process proc = new ProcessBuilder("bash", "-lc", "fuser -v " + target + " 2>/dev/null || true").start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            proc.waitFor();
            if (out.isBlank()) return "";
            if (out.length() > 140) out = out.substring(0, 140) + "...";
            return "occupied: " + out;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<Map<String, Object>> listMidiOutDevices() {
        List<Map<String, Object>> list = new ArrayList<>();
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        int idx = 1;
        for (MidiDevice.Info info : infos) {
            try {
                MidiDevice d = MidiSystem.getMidiDevice(info);
                if (d.getMaxReceivers() == 0) continue;
                Map<String, Object> m = new ConcurrentHashMap<>();
                m.put("index", idx++);
                m.put("name", info.getName());
                m.put("vendor", info.getVendor());
                m.put("desc", info.getDescription());
                m.put("version", info.getVersion());
                list.add(m);
            } catch (Exception ignored) {}
        }
        return list;
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
