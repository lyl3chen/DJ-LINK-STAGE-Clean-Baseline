package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Titan BPM Master HTTP 输出驱动。
 */
public class TitanApiDriver implements OutputDriver {
    private final TitanAdapter adapter = new TitanAdapter();

    private volatile boolean running = false;
    private volatile boolean enabled = false;
    private volatile String error = "";

    private volatile String titanIp = "127.0.0.1";
    private volatile String baseUrl = "http://127.0.0.1:4430";
    private volatile String versionMode = "auto";
    private volatile int versionDetected = 0;
    private volatile int masterIndex = 0;
    private volatile int rateLimitMs = 500;

    private volatile int lastSentBpmInt = -1;
    private volatile long lastSendTs = 0L;
    private volatile int lastHttpCode = 0;

    @Override
    public String name() { return "titanApi"; }

    @Override
    public synchronized void start(Map<String, Object> config) {
        enabled = true;
        applyConfig(config);
        try {
            adapter.configure(baseUrl, versionMode);
            adapter.connectAndScan();
            versionDetected = adapter.getDetectedVersion();
            running = true;
            error = "";
        } catch (Exception e) {
            running = false;
            error = "titan init failed: " + e.getMessage();
        }
    }

    @Override
    public synchronized void stop() {
        enabled = false;
        running = false;
        error = "";
    }

    @Override
    public synchronized void update(Map<String, Object> state) {
        if (!running || state == null) return;
        Object bpmObj = state.get("masterBpm");
        Object pitchObj = state.get("sourcePitchPct");
        if (!(bpmObj instanceof Number)) return;

        double bpm = ((Number) bpmObj).doubleValue();
        if (pitchObj instanceof Number) {
            bpm = bpm * (1.0 + ((Number) pitchObj).doubleValue() / 100.0);
        }
        if (!Double.isFinite(bpm) || bpm <= 0) return;

        int bpmInt = (int) Math.round(bpm); // 必须整数发送
        bpmInt = Math.max(40, Math.min(240, bpmInt));

        long now = System.currentTimeMillis();
        if (bpmInt == lastSentBpmInt && (now - lastSendTs) < rateLimitMs) return;
        if ((now - lastSendTs) < rateLimitMs) return;

        try {
            int code = adapter.setBpm(masterIndex, bpmInt);
            versionDetected = adapter.getDetectedVersion();
            lastHttpCode = code;
            lastSentBpmInt = bpmInt;
            lastSendTs = now;
            error = "";
        } catch (Exception e) {
            error = "titan send failed: " + e.getMessage();
            // v11 可能 show 切换后 titanId 失效；下次 update 再自动恢复。
            try {
                adapter.connectAndScan();
                versionDetected = adapter.getDetectedVersion();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("running", running);
        m.put("ip", titanIp);
        m.put("baseUrl", baseUrl);
        m.put("versionMode", versionMode);
        m.put("versionDetected", versionDetected);
        m.put("masterIndex", masterIndex);
        m.put("rateLimitMs", rateLimitMs);
        m.put("lastSentBpm", lastSentBpmInt);
        m.put("lastSendTs", lastSendTs);
        m.put("lastHttpCode", lastHttpCode);
        m.put("error", error);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void applyConfig(Map<String, Object> config) {
        if (config == null) return;
        Object ip = config.get("ip");
        if (ip != null) titanIp = String.valueOf(ip).trim();

        Object url = config.get("baseUrl");
        if ((titanIp == null || titanIp.isBlank()) && url != null) {
            // 兼容旧配置：如果只给了 baseUrl，则提取 host 当 ip。
            try {
                String s = String.valueOf(url).trim();
                if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
                java.net.URI u = java.net.URI.create(s);
                if (u.getHost() != null) titanIp = u.getHost();
            } catch (Exception ignored) {}
        }
        if (titanIp == null || titanIp.isBlank()) titanIp = "127.0.0.1";
        baseUrl = "http://" + titanIp + ":4430";

        Object vm = config.get("versionMode");
        if (vm != null) versionMode = String.valueOf(vm).trim().toLowerCase();
        Object mi = config.get("masterIndex");
        if (mi instanceof Number) masterIndex = Math.max(0, Math.min(3, ((Number) mi).intValue()));
        Object rl = config.get("rateLimitMs");
        if (rl instanceof Number) rateLimitMs = Math.max(200, Math.min(5000, ((Number) rl).intValue()));
    }
}
