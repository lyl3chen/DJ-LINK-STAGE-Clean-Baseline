package dbclient.sync;

import dbclient.config.UserSettingsStore;
import dbclient.sync.drivers.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signal output scheduler (LTC/MTC/Link/Console API) with unified driver lifecycle.
 */
public class SyncOutputManager {
    private static final SyncOutputManager INSTANCE = new SyncOutputManager();
    private final Map<String, OutputDriver> drivers = new LinkedHashMap<>();
    private final Map<String, Object> lastSemantic = new ConcurrentHashMap<>();
    private final TimecodeClock clock = new TimecodeClock();
    private volatile String sourceState = "OFFLINE";
    private volatile Integer sourcePlayer = null;
    private volatile double lastTimeSec = 0.0;

    public static SyncOutputManager getInstance() { return INSTANCE; }

    private SyncOutputManager() {
        register(new LtcDriver());
        register(new MtcDriver());
        register(new AbletonLinkDriver());
        register(new TitanApiDriver());
        register(new Ma2BpmDriver());
        register(new ConsoleApiDriver());
    }

    private void register(OutputDriver d) { drivers.put(d.name(), d); }

    private volatile boolean applying = false;

    @SuppressWarnings("unchecked")
    public synchronized void applySettings() {
        if (applying) return;
        applying = true;
        new Thread(() -> {
            try {
                Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
                Map<String, Object> sync = (Map<String, Object>) settings.getOrDefault("sync", Map.of());
                for (Map.Entry<String, OutputDriver> e : drivers.entrySet()) {
                    Map<String, Object> cfg = sync.get(e.getKey()) instanceof Map ? (Map<String, Object>) sync.get(e.getKey()) : Map.of();
                    boolean enabled = Boolean.TRUE.equals(cfg.get("enabled"));
                    // 配置保存后重启驱动，确保修改立即生效。
                    try { e.getValue().stop(); } catch (Exception ignored) {}
                    if (enabled) {
                        try { e.getValue().start(cfg); } catch (Exception ignored) {}
                    }
                }
            } finally {
                applying = false;
            }
        }, "sync-apply-settings").start();
    }

    public synchronized void onSemanticEvent(String event, Integer player, Map<String, Object> data) {
        lastSemantic.put("event", event);
        lastSemantic.put("player", player);
        lastSemantic.put("data", data != null ? data : Map.of());
        broadcastState(buildDerivedState());
    }

    @SuppressWarnings("unchecked")
    public synchronized void onPlayersState(Map<String, Object> playersState) {
        Map<String, Object> derived = buildDerivedState();
        if (playersState != null) {
            Object players = playersState.get("players");
            if (players instanceof List) {
                List<?> list = (List<?>) players;
                Map<String, Object> chosen = null;

                Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
                Map<String, Object> sync = settings.get("sync") instanceof Map ? (Map<String, Object>) settings.get("sync") : Map.of();
                String sourceMode = String.valueOf(sync.getOrDefault("sourceMode", "master"));
                int selectedPlayer = sync.get("masterPlayer") instanceof Number ? ((Number) sync.get("masterPlayer")).intValue() : 1;

                // A) 手动模式：优先使用指定 player（可播放或暂停都跟随它的时间）
                if ("manual".equalsIgnoreCase(sourceMode)) {
                    for (Object o : list) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> p = (Map<String, Object>) o;
                        int num = p.get("number") instanceof Number ? ((Number) p.get("number")).intValue() : -1;
                        if (num == selectedPlayer && Boolean.TRUE.equals(p.get("active"))) {
                            chosen = p; break;
                        }
                    }
                }

                // B) 跟随 master（默认）
                if (chosen == null) {
                    for (Object o : list) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> p = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(p.get("master")) && Boolean.TRUE.equals(p.get("active"))) {
                            chosen = p; break;
                        }
                    }
                }

                // C) 兜底：任何在播放的 deck
                if (chosen == null) {
                    for (Object o : list) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> p = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(p.get("playing")) && Boolean.TRUE.equals(p.get("active"))) { chosen = p; break; }
                    }
                }

                if (chosen != null) {
                    Object ms = chosen.get("currentTimeMs");
                    if (!(ms instanceof Number)) ms = chosen.get("beatTimeMs");
                    Object bpm = chosen.get("bpm");
                    Object pitch = chosen.get("pitch");
                    boolean playing = Boolean.TRUE.equals(chosen.get("playing"));
                    boolean active = !Boolean.FALSE.equals(chosen.get("active"));

                    double nowSec = ms instanceof Number ? ((Number) ms).doubleValue()/1000.0 : lastTimeSec;
                    if (ms instanceof Number) {
                        lastTimeSec = nowSec;
                    } else if (!playing) {
                        // 换歌到未播放且时间字段缺失时，避免沿用上一首结尾时间。
                        nowSec = 0.0;
                        lastTimeSec = 0.0;
                    }

                    int beat = chosen.get("beat") instanceof Number ? ((Number) chosen.get("beat")).intValue() : -1;
                    boolean stoppedLike = !playing && (beat < 0 || nowSec <= 0.05);
                    if (stoppedLike) {
                        lastTimeSec = 0.0;
                    }

                    double speed = 1.0;
                    if (pitch instanceof Number) speed = 1.0 + ((Number) pitch).doubleValue() / 100.0;
                    clock.ingestReference(lastTimeSec, playing, speed);
                    double outSec = stoppedLike ? 0.0 : clock.nowSeconds();

                    derived.put("masterTimeSec", outSec);
                    if (bpm instanceof Number) derived.put("masterBpm", ((Number) bpm).doubleValue());
                    if (pitch instanceof Number) derived.put("sourcePitchPct", ((Number) pitch).doubleValue());
                    derived.put("sourcePlayer", chosen.get("number"));
                    derived.put("sourceMode", sourceMode);
                    derived.put("sourcePlaying", playing);
                    derived.put("sourceActive", active);
                    // 规则：非播放时，beat=-1 视为 STOPPED；否则视为 PAUSED（驻留）
                    String st = !active ? "OFFLINE" : (playing ? "PLAYING" : (beat < 0 || nowSec <= 0.05 ? "STOPPED" : "PAUSED"));
                    sourceState = st;
                    sourcePlayer = chosen.get("number") instanceof Number ? ((Number) chosen.get("number")).intValue() : null;
                    derived.put("sourceState", st);
                } else {
                    // 没有可用源时，回到离线并归零时码，避免显示残留时间。
                    sourceState = "OFFLINE";
                    sourcePlayer = null;
                    lastTimeSec = 0.0;
                    clock.ingestReference(0.0, false, 1.0);
                }
            }
        }
        broadcastState(derived);
    }

    private void broadcastState(Map<String, Object> state) {
        for (OutputDriver d : drivers.values()) d.update(state);
    }

    private Map<String, Object> buildDerivedState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("masterTimeSec", clock.nowSeconds());
        state.put("rawTimeSec", lastTimeSec);
        state.put("masterBpm", 120.0);
        state.put("sourcePlaying", false);
        state.put("sourceActive", false);
        state.put("sourceState", "OFFLINE");
        state.put("__clock", clock);
        state.put("semantic", new LinkedHashMap<>(lastSemantic));
        return state;
    }

    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> ds = new LinkedHashMap<>();
        for (OutputDriver d : drivers.values()) ds.put(d.name(), d.status());
        out.put("drivers", ds);
        out.put("sourceState", sourceState);
        out.put("sourcePlayer", sourcePlayer);

        boolean ltcEnabled = ds.get("ltc") instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) ds.get("ltc")).get("running"));
        boolean mtcEnabled = ds.get("mtc") instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) ds.get("mtc")).get("running"));
        boolean anyTcEnabled = ltcEnabled || mtcEnabled;

        double displaySec = clock.nowSeconds();
        if (!anyTcEnabled || "OFFLINE".equals(sourceState) || "STOPPED".equals(sourceState)) {
            displaySec = 0.0;
        }

        out.put("rawTimeSec", anyTcEnabled ? lastTimeSec : 0.0);
        out.put("timecode", toTimecode(displaySec, 25));
        out.put("semantic", new LinkedHashMap<>(lastSemantic));
        return out;
    }

    public synchronized Map<String, Object> sendMa2TestBpm(double bpm) {
        OutputDriver d = drivers.get("ma2Telnet");
        if (d instanceof Ma2BpmDriver) {
            return ((Ma2BpmDriver) d).sendTestBpm(bpm);
        }
        return Map.of("ok", false, "error", "ma2Telnet driver not available");
    }

    public synchronized Map<String, Object> sendMa2TestCommand(String command) {
        OutputDriver d = drivers.get("ma2Telnet");
        if (d instanceof Ma2BpmDriver) {
            return ((Ma2BpmDriver) d).sendTestCommand(command);
        }
        return Map.of("ok", false, "connected", false, "sentCommand", command, "rawResponse", "", "error", "ma2Telnet driver not available");
    }

    private String toTimecode(double sec, int fps) {
        int total = (int) Math.max(0, Math.floor(sec));
        int hh = total / 3600;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        int ff = (int) Math.floor((sec - Math.floor(sec)) * Math.max(1, fps));
        return String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff);
    }
}
