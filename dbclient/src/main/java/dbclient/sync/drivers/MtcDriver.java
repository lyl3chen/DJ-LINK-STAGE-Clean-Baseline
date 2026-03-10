package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

public class MtcDriver implements OutputDriver {
    private volatile boolean running;
    private volatile int qfCounter;
    private Map<String, Object> cfg = new LinkedHashMap<>();

    public String name() { return "mtc"; }

    public void start(Map<String, Object> config) {
        running = true;
        cfg = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
    }

    public void stop() { running = false; }

    public void update(Map<String, Object> state) {
        if (!running) return;
        qfCounter = (qfCounter + 1) % 8;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("quarterFrame", qfCounter);
        m.put("midiPort", cfg.getOrDefault("midiPort", ""));
        return m;
    }
}
