package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

public class MtcDriver implements OutputDriver {
    // 这是 MTC 占位驱动：先模拟 quarter-frame 计数，后续接真实 MIDI 输出。
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
