package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.*;

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
    private volatile List<Integer> masterIndices = new ArrayList<>(List.of(0));
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
        // masterBpm 已经是有效 BPM（包含 pitch），直接使用，不再重复叠加 pitch
        if (!(bpmObj instanceof Number)) return;

        double bpm = ((Number) bpmObj).doubleValue();
        if (!Double.isFinite(bpm) || bpm <= 0) return;

        int bpmInt = (int) Math.round(bpm); // 必须整数发送
        bpmInt = Math.max(40, Math.min(240, bpmInt));

        long now = System.currentTimeMillis();
        if (bpmInt == lastSentBpmInt && (now - lastSendTs) < rateLimitMs) return;
        if ((now - lastSendTs) < rateLimitMs) return;

        try {
            int code = 0;
            List<Integer> targets = masterIndices == null || masterIndices.isEmpty() ? List.of(0) : new ArrayList<>(masterIndices);
            for (Integer idx : targets) {
                if (idx == null) continue;
                code = adapter.setBpm(Math.max(0, Math.min(3, idx)), bpmInt);
            }
            versionDetected = adapter.getDetectedVersion();
            lastHttpCode = code;
            lastSentBpmInt = bpmInt;
            lastSendTs = now;
            error = "";
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg != null && msg.toLowerCase().contains("connection refused")) {
                error = "titan send failed: 连接被拒绝（请检查 Titan IP 与控台 WebAPI 是否开启）";
            } else {
                error = "titan send failed: " + msg;
            }
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
        // 主层字段
        m.put("enabled", enabled);
        m.put("running", running);
        m.put("connected", !error.isEmpty() || lastHttpCode == 200);  // 统一为 connected
        m.put("lastSentBpm", lastSentBpmInt);
        m.put("error", error);

        // 兼容旧字段
        m.put("ip", titanIp);
        m.put("baseUrl", baseUrl);
        m.put("versionMode", versionMode);
        m.put("versionDetected", versionDetected);
        m.put("masterIndices", new ArrayList<>(masterIndices));
        m.put("rateLimitMs", rateLimitMs);
        m.put("lastSendTs", lastSendTs);
        m.put("lastHttpCode", lastHttpCode);

        // 诊断层
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("ip", titanIp);
        diagnostics.put("baseUrl", baseUrl);
        diagnostics.put("versionMode", versionMode);
        diagnostics.put("versionDetected", versionDetected);
        diagnostics.put("masterIndices", new ArrayList<>(masterIndices));
        diagnostics.put("rateLimitMs", rateLimitMs);
        diagnostics.put("lastSentBpm", lastSentBpmInt);
        diagnostics.put("lastSendTs", lastSendTs);
        diagnostics.put("lastHttpCode", lastHttpCode);
        m.put("diagnostics", diagnostics);

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

        List<Integer> parsed = new ArrayList<>();
        Object mis = config.get("masterIndices");
        if (mis instanceof List) {
            for (Object o : (List<?>) mis) {
                if (o instanceof Number) parsed.add(Math.max(0, Math.min(3, ((Number) o).intValue())));
                else {
                    try { parsed.add(Math.max(0, Math.min(3, Integer.parseInt(String.valueOf(o))))); } catch (Exception ignored) {}
                }
            }
        }
        if (parsed.isEmpty()) {
            Object mi = config.get("masterIndex"); // 兼容旧单选
            if (mi instanceof Number) parsed.add(Math.max(0, Math.min(3, ((Number) mi).intValue())));
            else parsed.add(0);
        }
        // 去重并稳定顺序
        LinkedHashSet<Integer> uniq = new LinkedHashSet<>(parsed);
        masterIndices = new ArrayList<>(uniq);

        Object rl = config.get("rateLimitMs");
        if (rl instanceof Number) rateLimitMs = Math.max(200, Math.min(5000, ((Number) rl).intValue()));
    }
}
