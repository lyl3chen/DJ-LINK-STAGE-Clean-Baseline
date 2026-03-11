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
        // Carabiner drives Link session directly; no custom UDP bridge updates anymore.
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        Map<String, Object> s = engine.status();
        running = Boolean.TRUE.equals(s.get("running"));
        m.put("running", running);
        m.putAll(s);
        String e = String.valueOf(s.getOrDefault("error", ""));
        m.put("error", e);
        return m;
    }
}
