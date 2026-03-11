package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ableton Link driver backed ONLY by lib-carabiner.
 */
public class AbletonLinkDriver implements OutputDriver {
    private volatile boolean running;
    private volatile boolean enabled;
    private volatile String error = "";
    private volatile Integer sourcePlayer = null;
    private volatile String sourceState = "OFFLINE";
    private volatile boolean sourcePlaying = false;
    private final CarabinerLinkEngine engine = new CarabinerLinkEngine();

    public String name() { return "abletonLink"; }

    public synchronized void start(Map<String, Object> config) {
        enabled = true;
        engine.start(config);
        Map<String, Object> s = engine.status();
        running = Boolean.TRUE.equals(s.get("running"));
        error = String.valueOf(s.getOrDefault("error", ""));
    }

    public synchronized void stop() {
        enabled = false;
        engine.stop();
        running = false;
    }

    public void update(Map<String, Object> state) {
        if (state == null) return;
        Object bpm = state.get("masterBpm");
        Object playing = state.get("sourcePlaying");
        Object sp = state.get("sourcePlayer");
        Object ss = state.get("sourceState");

        Double b = bpm instanceof Number ? ((Number) bpm).doubleValue() : null;
        Boolean p = playing instanceof Boolean ? (Boolean) playing : null;
        engine.updateFromSource(b, p);

        sourcePlaying = Boolean.TRUE.equals(p);
        sourcePlayer = sp instanceof Number ? ((Number) sp).intValue() : sourcePlayer;
        sourceState = ss == null ? sourceState : String.valueOf(ss);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("sourcePlayer", sourcePlayer);
        m.put("sourceState", sourceState);
        m.put("sourcePlaying", sourcePlaying);
        Map<String, Object> s = engine.status();
        running = Boolean.TRUE.equals(s.get("running"));
        m.put("running", running);
        m.putAll(s);
        String e = String.valueOf(s.getOrDefault("error", ""));
        m.put("error", e);
        return m;
    }
}
