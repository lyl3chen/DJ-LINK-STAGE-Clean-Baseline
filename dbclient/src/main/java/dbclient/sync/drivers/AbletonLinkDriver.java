package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ableton Link 上层驱动（事件触发式 Measure 同步）
 *
 * 职责：
 * - 从 SyncOutputManager 获取统一状态
 * - 提取 BPM 和 Playing 状态发给 CarabinerLinkEngine
 * - 通过 AbletonLinkBeatBridge 实现事件触发式 Measure 对齐
 * - 不回读 Carabiner 状态控制主源
 */
public class AbletonLinkDriver implements OutputDriver {
    private volatile boolean running;
    private volatile boolean enabled;
    private volatile String error = "";
    private final CarabinerLinkEngine engine = new CarabinerLinkEngine();
    private final AbletonLinkBeatBridge beatBridge = new AbletonLinkBeatBridge(engine);

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
     * BeatBridge 内部检测事件并触发 Measure 对齐
     * 不回读 Carabiner 状态
     */
    public void update(Map<String, Object> state) {
        if (state == null) return;

        // masterBpm 已经是有效 BPM（包含 pitch 调整），直接使用，不再重复叠加 pitch
        Object bpm = state.get("masterBpm");
        Object pitchPct = state.get("sourcePitchPct");
        Object playing = state.get("sourcePlaying");

        // masterBpm 已经是有效 BPM，直接使用
        Double b = null;
        if (bpm instanceof Number) {
            b = ((Number) bpm).doubleValue();
            // 不再重复叠加 pitch，因为 masterBpm 已经包含 pitch
        }
        
        // 调试日志
        System.out.println("[AbletonLinkDriver] TEMPO_SEND: sourceType=" + state.get("sourceType") + 
            ", trackBaseBpm=" + state.get("trackBaseBpm") + 
            ", pitch=" + (pitchPct instanceof Number ? pitchPct : 0) + 
            ", masterBpm=" + b);

        Boolean p = playing instanceof Boolean ? (Boolean) playing : null;

        // 更新 BeatBridge 主源状态（内部检测事件并触发对齐）
        beatBridge.updateMasterState(state);

        // 只发 BPM 和 Playing，不回读
        engine.updateFromSource(b, p);
    }

    /**
     * 手动触发 Measure 对齐
     */
    public void manualResync() {
        beatBridge.manualResync();
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
