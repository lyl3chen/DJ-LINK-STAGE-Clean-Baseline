package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

public class AbletonLinkDriver implements OutputDriver {
    // 第一阶段最小版：tempo/beatPosition/playing 同步到本地 bridge。
    private volatile boolean running;
    private volatile boolean enabled;
    private volatile double tempo = 120.0;
    private volatile double beatPosition = 0.0;
    private volatile boolean playing = false;
    private volatile long lastUpdateTs = 0L;
    private volatile String error = "";

    private volatile Integer sourcePlayer = null;
    private volatile String sourceState = "OFFLINE";

    private volatile long lastSendTs = 0L;
    private static final long SEND_INTERVAL_MS = 25; // 40Hz
    private static final long RECONNECT_INTERVAL_MS = 5000;
    private long lastReconnectTryMs = 0L;

    private AbletonLinkBridgeClient bridge;
    private final AbletonLinkBridgeProcess bridgeProcess = new AbletonLinkBridgeProcess();

    public String name() { return "abletonLink"; }

    public synchronized void start(Map<String, Object> config) {
        enabled = true;
        running = true;

        String host = strCfg(config, "bridgeHost", "127.0.0.1");
        int sendPort = intCfg(config, "bridgeSendPort", 19110);
        int listenPort = intCfg(config, "bridgeListenPort", 19111);

        bridge = new AbletonLinkBridgeClient(host, sendPort, listenPort);
        bridge.start();
        error = "";
    }

    public synchronized void stop() {
        running = false;
        enabled = false;
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        bridgeProcess.stop();
    }

    public void update(Map<String, Object> state) {
        if (!running || state == null) return;

        Object bpm = state.get("masterBpm");
        Object sec = state.get("masterTimeSec");
        Object sp = state.get("sourcePlaying");
        Object player = state.get("sourcePlayer");
        Object sst = state.get("sourceState");

        if (bpm instanceof Number) tempo = ((Number) bpm).doubleValue();
        double masterTimeSec = sec instanceof Number ? ((Number) sec).doubleValue() : 0.0;
        playing = Boolean.TRUE.equals(sp);
        sourcePlayer = player instanceof Number ? ((Number) player).intValue() : null;
        sourceState = sst == null ? "OFFLINE" : String.valueOf(sst);

        // 第一阶段固定换算：beatPosition = masterTimeSec * tempo / 60.0
        beatPosition = masterTimeSec * tempo / 60.0;
        lastUpdateTs = System.currentTimeMillis();

        if (bridge == null) {
            tryReconnect();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSendTs < SEND_INTERVAL_MS) return;
        lastSendTs = now;

        bridge.sendSync(tempo, beatPosition, playing, now, sourcePlayer, sourceState);

        Map<String, Object> bs = bridge.status();
        Object berr = bs.get("error");
        error = berr == null ? "" : String.valueOf(berr);

        if (!Boolean.TRUE.equals(bs.get("bridgeRunning"))) {
            tryReconnect();
        }
    }

    public synchronized Map<String, Object> startBridgeProcess() {
        return bridgeProcess.start();
    }

    public synchronized Map<String, Object> stopBridgeProcess() {
        return bridgeProcess.stop();
    }

    public synchronized Map<String, Object> bridgeProcessStatus() {
        return bridgeProcess.status();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("enabled", enabled);
        m.put("tempo", tempo);
        m.put("beatPosition", beatPosition);
        m.put("playing", playing);
        m.put("sourcePlayer", sourcePlayer);
        m.put("sourceState", sourceState);
        m.put("lastUpdateTs", lastUpdateTs);

        Map<String, Object> ps = bridgeProcess.status();
        m.put("bridgeRunning", ps.getOrDefault("bridgeRunning", false));
        m.put("bridgePid", ps.getOrDefault("bridgePid", 0L));
        m.put("bridgeStartedAt", ps.getOrDefault("bridgeStartedAt", 0L));
        m.put("bridgeError", ps.getOrDefault("bridgeError", ""));
        m.put("bridgeExitCode", ps.getOrDefault("bridgeExitCode", 0));
        m.put("bridgeLastExitReason", ps.getOrDefault("bridgeLastExitReason", ""));
        m.put("bridgeLastStdout", ps.getOrDefault("bridgeLastStdout", ""));
        m.put("bridgeLastStderr", ps.getOrDefault("bridgeLastStderr", ""));
        m.put("bridgeKilledByManager", ps.getOrDefault("bridgeKilledByManager", false));

        // 默认先放“进程视角”的后端状态，避免 bridge client 未收到 ACK 时误报。
        m.put("backendMode", ps.getOrDefault("backendMode", "ack-only"));
        m.put("backendLoaded", ps.getOrDefault("backendLoaded", false));
        m.put("backendInitError", ps.getOrDefault("backendInitError", ""));
        m.put("backendVersion", "");
        m.put("peerDetectionWorking", "unknown");
        m.put("numPeers", 0);
        m.put("maxPeersSeen", 0);
        m.put("lastPeerChangeTs", 0L);
        m.put("peerSampleCount", 0L);
        m.put("firstPeerSeenTs", 0L);
        m.put("lastPeerEventTs", 0L);
        m.put("peerEventCount", 0L);
        m.put("interfacesSummary", "");
        m.put("primaryInterfaceHint", "");
        m.put("discoveryActive", "unknown");
        m.put("lastAckTs", 0);

        if (bridge != null) {
            Map<String, Object> bs = bridge.status();
            long ackTs = bs.get("lastAckTs") instanceof Number ? ((Number) bs.get("lastAckTs")).longValue() : 0L;
            m.put("numPeers", bs.getOrDefault("numPeers", 0));
            m.put("maxPeersSeen", bs.getOrDefault("maxPeersSeen", 0));
            m.put("lastPeerChangeTs", bs.getOrDefault("lastPeerChangeTs", 0L));
            m.put("peerSampleCount", bs.getOrDefault("peerSampleCount", 0L));
            m.put("firstPeerSeenTs", bs.getOrDefault("firstPeerSeenTs", 0L));
            m.put("lastPeerEventTs", bs.getOrDefault("lastPeerEventTs", 0L));
            m.put("peerEventCount", bs.getOrDefault("peerEventCount", 0L));
            m.put("interfacesSummary", bs.getOrDefault("interfacesSummary", ""));
            m.put("primaryInterfaceHint", bs.getOrDefault("primaryInterfaceHint", ""));
            m.put("discoveryActive", bs.getOrDefault("discoveryActive", "unknown"));
            m.put("lastAckTs", ackTs);
            // 仅当收到过 ACK 时，才用 bridge client 的后端字段覆盖进程字段。
            if (ackTs > 0) {
                m.put("backendMode", bs.getOrDefault("backendMode", m.get("backendMode")));
                m.put("backendLoaded", bs.getOrDefault("backendLoaded", m.get("backendLoaded")));
                m.put("backendVersion", bs.getOrDefault("backendVersion", ""));
                m.put("peerDetectionWorking", bs.getOrDefault("peerDetectionWorking", "unknown"));
                m.put("backendInitError", bs.getOrDefault("backendInitError", m.get("backendInitError")));
            }
            if (error == null || error.isEmpty()) {
                Object be = bs.get("error");
                String ee = be == null ? "" : String.valueOf(be);
                if (ee.isEmpty()) ee = String.valueOf(ps.getOrDefault("bridgeError", ""));
                m.put("error", ee);
            } else {
                m.put("error", error);
            }
        } else {
            String ee = error == null ? "" : error;
            if (ee.isEmpty()) ee = String.valueOf(ps.getOrDefault("bridgeError", ""));
            m.put("error", ee);
        }
        return m;
    }

    private synchronized void tryReconnect() {
        long now = System.currentTimeMillis();
        if (now - lastReconnectTryMs < RECONNECT_INTERVAL_MS) return;
        lastReconnectTryMs = now;
        if (bridge != null) {
            try { bridge.stop(); } catch (Exception ignored) {}
        }
        // 简单重连：使用默认端口重建
        bridge = new AbletonLinkBridgeClient("127.0.0.1", 19110, 19111);
        bridge.start();
    }

    private static int intCfg(Map<String, Object> cfg, String key, int def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    private static String strCfg(Map<String, Object> cfg, String key, String def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        return v == null ? def : String.valueOf(v);
    }
}
