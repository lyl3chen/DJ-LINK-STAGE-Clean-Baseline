package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ableton Link 上层驱动（仅 lib-carabiner 路线）。
 * 说明：
 * - 这里不再管理任何 Node/C++ bridge 进程；
 * - 仅负责把统一播放源状态喂给 CarabinerLinkEngine；
 * - 再把引擎状态整理成 /api/sync/state 可读字段。
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

    /**
     * 跟随统一播放源模式：
     * - 使用 SyncOutputManager 派生的 masterBpm/sourcePlaying/sourcePlayer/sourceState；
     * - 按当前业务需求，Link 侧 BPM 会叠加 sourcePitchPct（变速后速度）。
     */
    public void update(Map<String, Object> state) {
        if (state == null) return;
        Object bpm = state.get("masterBpm");
        Object pitchPct = state.get("sourcePitchPct");
        Object playing = state.get("sourcePlaying");
        Object sp = state.get("sourcePlayer");
        Object ss = state.get("sourceState");
        Object sec = state.get("masterTimeSec");

        Double b = bpm instanceof Number ? ((Number) bpm).doubleValue() : null;
        if (b != null && pitchPct instanceof Number) {
            b = b * (1.0 + ((Number) pitchPct).doubleValue() / 100.0);
        }
        Double beatPos = null;
        if (sec instanceof Number && b != null) {
            beatPos = ((Number) sec).doubleValue() * b / 60.0;
        }
        Boolean p = playing instanceof Boolean ? (Boolean) playing : null;
        engine.updateFromSource(b, beatPos, p);

        sourcePlaying = Boolean.TRUE.equals(p);
        if (sp instanceof Number) {
            sourcePlayer = ((Number) sp).intValue();
        }
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
