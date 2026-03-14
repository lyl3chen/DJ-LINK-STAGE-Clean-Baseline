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
    private final TimecodeTimeline timeline = TimecodeTimeline.getInstance();
    private final TimecodeSourceResolver sourceResolver = TimecodeSourceResolver.getInstance();
    private volatile String sourceState = "OFFLINE";
    private volatile Integer sourcePlayer = null;
    private volatile double lastTimeSec = 0.0;

    public static SyncOutputManager getInstance() { return INSTANCE; }

    private SyncOutputManager() {
        // 新驱动（默认）
        register(new LtcDriver2());
        register(new MtcDriver2());
        // 旧驱动（legacy，仅保留）
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

    private List<Map<String, Object>> lastPlayersList = new ArrayList<>();
    
    @SuppressWarnings("unchecked")
    public synchronized void onPlayersState(Map<String, Object> playersState) {
        // 保存 player 列表供 timecodeSource 查找
        this.lastPlayersList.clear();
        if (playersState != null) {
            Object players = playersState.get("players");
            if (players instanceof List) {
                for (Object o : (List<?>) players) {
                    if (o instanceof Map) {
                        Map<String, Object> p = (Map<String, Object>) o;
                        lastPlayersList.add(p);
                    }
                }
            }
        }
        
        // 更新 TimecodeSourceResolver
        sourceResolver.updatePlayers(lastPlayersList);
        
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
                    // 传递媒体位置和身份字段，供 LTC re-anchor 使用
                    if (ms instanceof Number) derived.put("currentTimeMs", ((Number) ms).longValue());
                    if (ms instanceof Number) derived.put("beatTimeMs", ((Number) ms).longValue());
                    derived.put("remainingTimeMs", chosen.get("remainingTimeMs"));
                    derived.put("playerId", chosen.get("number"));
                    derived.put("trackId", chosen.get("trackId"));
                    derived.put("rekordboxId", chosen.get("rekordboxId"));
                    derived.put("playing", playing);
                    if (bpm instanceof Number) derived.put("bpm", ((Number) bpm).doubleValue());
                    if (pitch instanceof Number) derived.put("pitch", ((Number) pitch).doubleValue());
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
                    // 传递空值，供 LTC re-anchor 检测离线状态
                    derived.put("currentTimeMs", 0L);
                    derived.put("beatTimeMs", 0L);
                    derived.put("remainingTimeMs", 0L);
                    derived.put("playerId", 0);
                    derived.put("trackId", "");
                    derived.put("rekordboxId", "");
                    derived.put("playing", false);
                    derived.put("bpm", 120.0);
                    derived.put("pitch", 0.0);
                }
            }
        }
        
        // 更新 TimecodeTimeline（供新驱动使用）
        updateTimeline(derived);
        
        broadcastState(derived);
    }
    
    private void updateTimeline(Map<String, Object> derived) {
        int playerId = 0;
        long currentTimeMs = 0;
        boolean playing = false;
        boolean active = false;
        
        Object pid = derived.get("playerId");
        if (pid instanceof Number) playerId = ((Number) pid).intValue();
        
        Object ctm = derived.get("currentTimeMs");
        if (ctm instanceof Number) currentTimeMs = ((Number) ctm).longValue();
        
        playing = Boolean.TRUE.equals(derived.get("playing"));
        String st = String.valueOf(derived.get("sourceState"));
        active = !"OFFLINE".equals(st) && !"STOPPED".equals(st);
        
        timeline.updateFromSource(playerId, currentTimeMs, playing, active, String.valueOf(derived.getOrDefault("trackId", "")));
    }

    private void broadcastState(Map<String, Object> derived) {
        // 获取 timecodeSource 配置
        Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
        Map<String, Object> sync = settings.get("sync") instanceof Map ? (Map<String, Object>) settings.get("sync") : Map.of();
        int timecodeSource = sync.get("timecodeSource") instanceof Number ? ((Number) sync.get("timecodeSource")).intValue() : 0;

        // 构建 timecode derived (LTC/MTC 用) - 手动指定 source；未选择或无效时明确 NO_SOURCE
        Map<String, Object> timecodeDerived = buildNoSourceDerived();
        if (timecodeSource > 0) {
            Map<String, Object> selected = buildTimecodeDerived(timecodeSource);
            if (selected != null) {
                timecodeDerived = selected;
            }
        }

        // 传给各 driver
        for (Map.Entry<String, OutputDriver> e : drivers.entrySet()) {
            String name = e.getKey();
            OutputDriver d = e.getValue();
            if ("ltc".equals(name) || "mtc".equals(name)) {
                // LTC/MTC 使用 timecodeSource
                d.update(timecodeDerived);
            } else {
                // 其他 driver 使用自动选择的 derived
                d.update(derived);
            }
        }
    }

    private Map<String, Object> buildNoSourceDerived() {
        Map<String, Object> d = new LinkedHashMap<>(buildDerivedState());
        d.put("masterTimeSec", 0.0);
        d.put("rawTimeSec", 0.0);
        d.put("masterBpm", 120.0);
        d.put("sourcePitchPct", 0.0);
        d.put("sourcePlayer", null);
        d.put("sourcePlaying", false);
        d.put("sourceActive", false);
        d.put("sourceState", "NO_SOURCE");
        d.put("currentTimeMs", 0L);
        d.put("beatTimeMs", 0L);
        d.put("remainingTimeMs", 0L);
        d.put("playerId", 0);
        d.put("trackId", "");
        d.put("rekordboxId", "");
        d.put("playing", false);
        d.put("bpm", 120.0);
        d.put("pitch", 0.0);
        return d;
    }
    
    private Map<String, Object> buildTimecodeDerived(int playerNum) {
        // 从保存的 player 列表中查找指定 player
        for (Map<String, Object> p : lastPlayersList) {
            int num = p.get("number") instanceof Number ? ((Number) p.get("number")).intValue() : -1;
            if (num == playerNum && Boolean.TRUE.equals(p.get("active"))) {
                return buildPlayerDerived(p);
            }
        }
        return null;
    }
    
    private Map<String, Object> buildPlayerDerived(Map<String, Object> p) {
        Map<String, Object> derived = new LinkedHashMap<>(buildDerivedState());
        // 构建单个 player 的 derived
        Object ms = p.get("currentTimeMs");
        if (!(ms instanceof Number)) ms = p.get("beatTimeMs");
        Object bpm = p.get("bpm");
        Object pitch = p.get("pitch");
        boolean playing = Boolean.TRUE.equals(p.get("playing"));
        boolean active = !Boolean.FALSE.equals(p.get("active"));
        
        double nowSec = ms instanceof Number ? ((Number) ms).doubleValue()/1000.0 : 0.0;
        int beat = p.get("beat") instanceof Number ? ((Number) p.get("beat")).intValue() : -1;
        double speed = pitch instanceof Number ? 1.0 + ((Number) pitch).doubleValue()/100.0 : 1.0;
        
        derived.put("masterTimeSec", nowSec);
        if (bpm instanceof Number) derived.put("masterBpm", ((Number) bpm).doubleValue());
        if (pitch instanceof Number) derived.put("sourcePitchPct", ((Number) pitch).doubleValue());
        derived.put("sourcePlayer", p.get("number"));
        derived.put("sourcePlaying", playing);
        derived.put("sourceActive", active);
        if (ms instanceof Number) derived.put("currentTimeMs", ((Number) ms).longValue());
        if (ms instanceof Number) derived.put("beatTimeMs", ((Number) ms).longValue());
        derived.put("remainingTimeMs", p.get("remainingTimeMs"));
        derived.put("playerId", p.get("number"));
        derived.put("trackId", p.get("trackId"));
        derived.put("rekordboxId", p.get("rekordboxId"));
        derived.put("playing", playing);
        if (bpm instanceof Number) derived.put("bpm", ((Number) bpm).doubleValue());
        if (pitch instanceof Number) derived.put("pitch", ((Number) pitch).doubleValue());
        
        String st = !active ? "OFFLINE" : (playing ? "PLAYING" : "PAUSED");
        derived.put("sourceState", st);
        return derived;
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

        boolean ltcEnabled = ds.get("ltc") instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) ds.get("ltc")).get("running"));
        boolean mtcEnabled = ds.get("mtc") instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) ds.get("mtc")).get("running"));
        boolean anyTcEnabled = ltcEnabled || mtcEnabled;

        Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
        Map<String, Object> sync = settings.get("sync") instanceof Map ? (Map<String, Object>) settings.get("sync") : Map.of();
        int timecodeSource = sync.get("timecodeSource") instanceof Number ? ((Number) sync.get("timecodeSource")).intValue() : 0;
        boolean tcSourceValid = timecodeSource > 0 && buildTimecodeDerived(timecodeSource) != null;

        String statusSourceState = sourceState;
        Integer statusSourcePlayer = sourcePlayer;
        double displaySec = clock.nowSeconds();

        if (anyTcEnabled && !tcSourceValid) {
            statusSourceState = "NO_SOURCE";
            statusSourcePlayer = null;
            displaySec = 0.0;
        } else if (!anyTcEnabled || "OFFLINE".equals(sourceState) || "STOPPED".equals(sourceState)) {
            displaySec = 0.0;
        }

        out.put("sourceState", statusSourceState);
        out.put("sourcePlayer", statusSourcePlayer);
        out.put("rawTimeSec", (anyTcEnabled && tcSourceValid) ? lastTimeSec : 0.0);
        out.put("timecode", toTimecode(displaySec, 25));
        
        // 新 timeline 状态
        out.put("timecodeSource", timecodeSource);
        out.put("timecodeSourceValid", tcSourceValid);
        out.put("timecodeTransportState", timeline.getPlayState().name());
        out.put("timecodeTimelineSec", timeline.getTimelineSec());
        out.put("timecodeSourcePlayer", timeline.getSourcePlayer());
        
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
