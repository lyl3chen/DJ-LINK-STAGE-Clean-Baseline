package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

public class LtcDriver implements OutputDriver {
    // 这是 LTC 占位驱动：先打通数据流，后续再接真实编码和声卡输出。
    private volatile boolean running;
    private volatile double seconds;
    private Map<String, Object> cfg = new LinkedHashMap<>();

    public String name() { return "ltc"; }

    public void start(Map<String, Object> config) {
        running = true;
        cfg = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
    }

    public void stop() { running = false; }

    public void update(Map<String, Object> state) {
        if (!running || state == null) return;
        Object t = state.get("masterTimeSec");
        if (t instanceof Number) seconds = ((Number) t).doubleValue();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("seconds", seconds);
        m.put("fps", cfg.getOrDefault("fps", 25));
        return m;
    }
}
