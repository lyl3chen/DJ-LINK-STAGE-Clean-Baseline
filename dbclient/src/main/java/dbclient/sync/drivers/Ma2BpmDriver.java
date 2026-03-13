package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MA2 BPM 同步驱动（当前阶段仅 BPM）。
 */
public class Ma2BpmDriver implements OutputDriver {
    private final Ma2TelnetClient client = new Ma2TelnetClient();

    private volatile boolean enabled = false;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    private volatile String host = "127.0.0.1";
    private volatile int port = 30000;
    private volatile String user = "remote";
    private volatile String pass = "1234";
    private volatile int rateLimitMs = 500;
    private volatile int minBpm = 40;
    private volatile int maxBpm = 240;
    private volatile boolean integerOnly = true;
    private volatile boolean onlyWhenPlaying = true;
    private volatile int speedMasterIndex = 1;
    private volatile String commandTemplate = "SpecialMaster 3.{index} At {bpm}";

    private volatile int lastSentBpm = -1;
    private volatile long lastSendTs = 0L;
    private volatile String lastCommand = "";
    private volatile String lastAck = "";
    private volatile String lastRawResponse = "";
    private volatile String error = "";
    private volatile long manualHoldUntilTs = 0L;

    @Override
    public String name() { return "ma2Telnet"; }

    @Override
    public synchronized void start(Map<String, Object> config) {
        enabled = true;
        applyConfig(config);
        try {
            client.configure(host, port, user, pass);
            client.connect();
            connected = client.isConnected();
            running = true;
            error = "";
        } catch (Exception e) {
            running = false;
            connected = false;
            error = "[MA2] error: " + e.getMessage();
        }
    }

    @Override
    public synchronized void stop() {
        enabled = false;
        running = false;
        connected = false;
        client.disconnect();
    }

    @Override
    public synchronized void update(Map<String, Object> state) {
        if (!running || state == null) return;
        Object bpmObj = state.get("masterBpm");
        Object pitchObj = state.get("sourcePitchPct");
        Object playingObj = state.get("sourcePlaying");
        boolean isPlaying = Boolean.TRUE.equals(playingObj);

        double raw = bpmObj instanceof Number ? ((Number) bpmObj).doubleValue() : -1;
        if (pitchObj instanceof Number && Double.isFinite(raw) && raw > 0) {
            raw = raw * (1.0 + ((Number) pitchObj).doubleValue() / 100.0);
        }
        System.out.println("[MASTER] bpm=" + raw + " playing=" + isPlaying);
        if (!Double.isFinite(raw) || raw <= 0 || raw == 65535.0) return;
        if (raw < minBpm || raw > maxBpm) return;

        if (onlyWhenPlaying && !isPlaying) {
            System.out.println("[BPM] skipped: not playing");
            return;
        }

        double shaped = integerOnly ? Math.round(raw) : raw;
        int bpmOut = (int) Math.round(shaped);
        System.out.println("[BPM] raw=" + raw + " rounded=" + bpmOut);

        long now = System.currentTimeMillis();
        if (now < manualHoldUntilTs) return;
        if (bpmOut == lastSentBpm) return;
        if (now - lastSendTs < rateLimitMs) {
            System.out.println("[BPM] skipped: rate limit");
            return;
        }

        String cmd = commandTemplate
                .replace("{index}", String.valueOf(speedMasterIndex))
                .replace("{bpm}", String.valueOf(bpmOut));
        try {
            ensureConnected();
            Ma2TelnetClient.CommandResult r = client.sendCommandDetailed(cmd);
            connected = client.isConnected();
            lastCommand = cmd;
            lastAck = r.ack;
            lastRawResponse = r.rawResponse;
            lastSentBpm = bpmOut;
            lastSendTs = now;
            error = "";
        } catch (Exception e) {
            connected = false;
            error = "[MA2] error: " + e.getMessage();
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("running", running);
        m.put("connected", connected);
        m.put("lastSentBpm", lastSentBpm);
        m.put("lastSendTs", lastSendTs);
        m.put("lastCommand", lastCommand);
        m.put("lastAck", lastAck);
        m.put("lastRawResponse", lastRawResponse);
        m.put("error", error);
        return m;
    }

    public synchronized Map<String, Object> sendTestBpm(double bpm) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputBpm", bpm);

        if (!Double.isFinite(bpm) || bpm <= 0 || bpm == 65535.0 || bpm < minBpm || bpm > maxBpm) {
            out.put("ok", false);
            out.put("filtered", true);
            out.put("reason", "invalid-range");
            return out;
        }

        int b = integerOnly ? (int) Math.round(bpm) : (int) Math.round(bpm);
        out.put("roundedBpm", b);

        long now = System.currentTimeMillis();
        if (b == lastSentBpm) {
            out.put("ok", false);
            out.put("filtered", true);
            out.put("reason", "duplicate");
            return out;
        }
        if (now - lastSendTs < rateLimitMs) {
            out.put("ok", false);
            out.put("filtered", true);
            out.put("reason", "rate-limit");
            return out;
        }

        String cmd = commandTemplate.replace("{index}", String.valueOf(speedMasterIndex)).replace("{bpm}", String.valueOf(b));
        Map<String, Object> sent = sendTestCommand(cmd);
        if (Boolean.TRUE.equals(sent.get("ok"))) {
            lastSentBpm = b;
            lastSendTs = now;
        }
        sent.put("inputBpm", bpm);
        sent.put("roundedBpm", b);
        sent.put("filtered", false);
        return sent;
    }

    public synchronized Map<String, Object> sendTestCommand(String cmd) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 手动测试时短暂暂停自动同步，避免测试值立刻被自动线程覆盖。
        manualHoldUntilTs = System.currentTimeMillis() + 5000;
        try {
            ensureConnected();
            Ma2TelnetClient.CommandResult r = client.sendCommandDetailed(cmd);
            connected = client.isConnected();
            lastCommand = r.sentCommand;
            lastAck = r.ack;
            lastRawResponse = r.rawResponse;
            error = "";
            out.put("connected", connected);
            out.put("sentCommand", r.sentCommand);
            out.put("rawResponse", r.rawResponse);
            out.put("error", "");
            out.put("ok", true);
        } catch (Exception e) {
            connected = false;
            error = "[MA2] error: " + e.getMessage();
            out.put("connected", false);
            out.put("sentCommand", cmd);
            out.put("rawResponse", "");
            out.put("error", error);
            out.put("ok", false);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void applyConfig(Map<String, Object> cfg) {
        if (cfg == null) return;
        if (cfg.get("host") != null) host = String.valueOf(cfg.get("host")).trim();
        if (cfg.get("port") instanceof Number) port = ((Number) cfg.get("port")).intValue();
        if (cfg.get("user") != null) user = String.valueOf(cfg.get("user"));
        if (cfg.get("pass") != null) pass = String.valueOf(cfg.get("pass"));
        if (cfg.get("rateLimitMs") instanceof Number) rateLimitMs = Math.max(100, ((Number) cfg.get("rateLimitMs")).intValue());
        if (cfg.get("minBpm") instanceof Number) minBpm = ((Number) cfg.get("minBpm")).intValue();
        if (cfg.get("maxBpm") instanceof Number) maxBpm = ((Number) cfg.get("maxBpm")).intValue();
        if (cfg.get("integerOnly") instanceof Boolean) integerOnly = (Boolean) cfg.get("integerOnly");
        if (cfg.get("onlyWhenPlaying") instanceof Boolean) onlyWhenPlaying = (Boolean) cfg.get("onlyWhenPlaying");
        if (cfg.get("speedMasterIndex") instanceof Number) speedMasterIndex = Math.max(1, Math.min(99, ((Number) cfg.get("speedMasterIndex")).intValue()));
        if (cfg.get("commandTemplate") != null) commandTemplate = String.valueOf(cfg.get("commandTemplate"));
    }

    private void ensureConnected() throws Exception {
        if (client.isConnected()) return;
        client.configure(host, port, user, pass);
        client.connect();
        connected = client.isConnected();
    }
}
