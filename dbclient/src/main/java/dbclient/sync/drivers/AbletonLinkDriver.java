package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ableton Link 上层驱动（最终修正版）
 *
 * 职责：
 * - 从 SyncOutputManager 获取统一状态
 * - 仅提取 BPM 和 Playing 状态发给 CarabinerLinkEngine
 * - 不回读 Carabiner 状态控制主源
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

    /**
     * 接收统一播放源状态，提取 BPM 和 Playing 发给 Link
     * 不回读 Carabiner 状态
     */
    public void update(Map<String, Object> state) {
        if (state == null) return;

        Object bpm = state.get("masterBpm");
        Object pitchPct = state.get("sourcePitchPct");
        Object playing = state.get("sourcePlaying");

        // 计算最终 BPM（叠加 pitch）
        Double b = null;
        if (bpm instanceof Number) {
            b = ((Number) bpm).doubleValue();
            if (pitchPct instanceof Number) {
                b = b * (1.0 + ((Number) pitchPct).doubleValue() / 100.0);
            }
        }

        Boolean p = playing instanceof Boolean ? (Boolean) playing : null;

        // 只发 BPM 和 Playing，不回读
        engine.updateFromSource(b, p);
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
