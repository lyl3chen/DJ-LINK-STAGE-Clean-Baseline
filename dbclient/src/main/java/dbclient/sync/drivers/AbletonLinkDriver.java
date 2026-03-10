package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

public class AbletonLinkDriver implements OutputDriver {
    private volatile boolean running;
    private volatile double tempo = 120.0;

    public String name() { return "abletonLink"; }

    public void start(Map<String, Object> config) { running = true; }
    public void stop() { running = false; }

    public void update(Map<String, Object> state) {
        if (!running || state == null) return;
        Object bpm = state.get("masterBpm");
        if (bpm instanceof Number) tempo = ((Number) bpm).doubleValue();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("tempo", tempo);
        return m;
    }
}
