package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

public class ConsoleApiDriver implements OutputDriver {
    private volatile boolean running;
    private volatile Map<String, Object> lastState = new LinkedHashMap<>();

    public String name() { return "consoleApi"; }

    public void start(Map<String, Object> config) { running = true; }
    public void stop() { running = false; }

    public void update(Map<String, Object> state) {
        if (!running || state == null) return;
        lastState = new LinkedHashMap<>(state);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("lastStateKeys", lastState.keySet());
        return m;
    }
}
