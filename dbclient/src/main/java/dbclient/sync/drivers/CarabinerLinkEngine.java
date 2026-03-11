package dbclient.sync.drivers;

import org.deepsymmetry.libcarabiner.Message;
import org.deepsymmetry.libcarabiner.Runner;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal Java-side manager for lib-carabiner Runner + TCP message stream.
 */
public class CarabinerLinkEngine {
    private final Runner runner = Runner.getInstance();

    private volatile boolean running = false;
    private volatile boolean supported = false;
    private volatile String error = "";
    private volatile String lastMessageType = "";
    private volatile long lastUpdateTs = 0L;
    private volatile double tempo = 120.0;
    private volatile double beatPosition = 0.0;
    private volatile boolean playing = false;
    private volatile int numPeers = 0;
    private volatile String version = "";

    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private Thread readThread;
    private Thread pingThread;

    private int port = 17000;
    private int updateIntervalMs = 20;

    @SuppressWarnings("unchecked")
    public synchronized void start(Map<String, Object> cfg) {
        supported = runner.canRunCarabiner();
        if (!supported) {
            running = false;
            error = "carabiner not supported on this platform";
            return;
        }
        port = intCfg(cfg, "port", 17000);
        updateIntervalMs = intCfg(cfg, "updateIntervalMs", 20);

        try {
            runner.setPort(port);
            runner.setUpdateInterval(updateIntervalMs);
            runner.start();
            running = true; // 必须先置 true，读写线程的 while 才会进入
            connectAndListen();
            error = "";
        } catch (Exception e) {
            running = false;
            error = "carabiner start failed: " + e.getMessage();
        }
    }

    public synchronized void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        reader = null;
        writer = null;
        try { runner.stop(); } catch (Exception ignored) {}
        if (readThread != null) readThread.interrupt();
        if (pingThread != null) pingThread.interrupt();
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("carabinerSupported", supported);
        m.put("carabinerRunning", running);
        m.put("tempo", tempo);
        m.put("beatPosition", beatPosition);
        m.put("playing", playing);
        m.put("numPeers", numPeers);
        m.put("version", version);
        m.put("lastMessageType", lastMessageType);
        m.put("lastUpdateTs", lastUpdateTs);
        m.put("error", error);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void onLine(String line) {
        try {
            Message m = new Message(line);
            lastMessageType = m.messageType;
            lastUpdateTs = System.currentTimeMillis();

            if ("version".equals(m.messageType) && m.details instanceof String) {
                version = (String) m.details;
            }

            if (("status".equals(m.messageType) || "beat-at-time".equals(m.messageType) || "phase-at-time".equals(m.messageType))
                    && m.details instanceof Map) {
                Map<String, Object> details = (Map<String, Object>) m.details;
                Object bpm = details.get("bpm");
                Object beat = details.get("beat");
                Object peers = details.get("peers");
                Object start = details.get("start");
                if (bpm instanceof Number) tempo = ((Number) bpm).doubleValue();
                if (beat instanceof Number) beatPosition = ((Number) beat).doubleValue();
                if (peers instanceof Number) numPeers = ((Number) peers).intValue();
                if (start instanceof Number) playing = ((Number) start).longValue() > 0L;
            }
            error = "";
        } catch (Exception e) {
            error = "carabiner message parse failed: " + e.getMessage();
        }
    }

    private void connectAndListen() throws Exception {
        IOException last = null;
        for (int i = 0; i < 30; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 800);
                s.setTcpNoDelay(true);
                socket = s;
                reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                startThreads();
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            }
        }
        throw new IOException("unable to connect to carabiner port " + port + ": " + (last == null ? "unknown" : last.getMessage()));
    }

    private void startThreads() {
        readThread = new Thread(() -> {
            try {
                String line;
                while (running && reader != null && (line = reader.readLine()) != null) {
                    onLine(line.trim());
                }
            } catch (Exception e) {
                if (running) error = "carabiner read failed: " + e.getMessage();
            }
        }, "carabiner-read");
        readThread.setDaemon(true);
        readThread.start();

        pingThread = new Thread(() -> {
            while (running && writer != null) {
                try {
                    writer.write("status\n");
                    writer.write("version\n");
                    writer.flush();
                } catch (Exception e) {
                    if (running) error = "carabiner write failed: " + e.getMessage();
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }, "carabiner-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    private static int intCfg(Map<String, Object> cfg, String key, int def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }
}
