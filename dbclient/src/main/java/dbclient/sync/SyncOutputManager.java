package dbclient.sync;

import dbclient.config.UserSettingsStore;
import dbclient.input.*;
import dbclient.media.analysis.BasicAudioAnalyzer;
import dbclient.media.library.InMemoryTrackRepository;
import dbclient.media.library.LocalLibraryService;
import dbclient.media.player.BasicLocalPlaybackEngine;
import dbclient.media.library.LocalLibraryService;
import dbclient.media.player.BasicLocalPlaybackEngine;
import dbclient.sync.drivers.*;
import dbclient.sync.timecode.TimecodeCore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signal output scheduler with unified driver lifecycle.
 *
 * 驱动列表：
 * - Ableton Link
 * - Titan API
 * - MA2 Telnet
 * - Console API
 * - LTC (通过 TimecodeCore)
 * - MTC (通过 TimecodeCore)
 */
public class SyncOutputManager {
    private static final SyncOutputManager INSTANCE = new SyncOutputManager();

    // 时间码核心（LTC/MTC 共用）
    private final TimecodeCore timecodeCore;

    // 输入源管理器（CDJ + Local Player）
    private final SourceInputManager sourceInputManager;

    // 驱动管理
    private final Map<String, OutputDriver> drivers = new LinkedHashMap<>();
    private final Map<String, Object> lastSemantic = new ConcurrentHashMap<>();

    /**
     * 统一输入状态字段（系统单一事实来源，供 API/TimecodeCore/输出驱动消费）
     * sourceState: OFFLINE | STOPPED | PLAYING | PAUSED
     * sourcePlayer: 当前主播放源编号（local 固定为 1；djlink 由主控播放器决定）
     * activeSourceType: djlink | local（由 /api/source/switch 控制）
     */
    private volatile String sourceState = "OFFLINE";
    private volatile Integer sourcePlayer = null;
    private volatile double lastTimeSec = 0.0;
    private volatile String activeSourceType = "djlink"; // "djlink" or "local"

    public static SyncOutputManager getInstance() { return INSTANCE; }

    private SyncOutputManager() {
        // 创建时间码核心（LTC/MTC 共用）
        timecodeCore = new TimecodeCore();

        // 创建输入源管理器
        sourceInputManager = new SourceInputManager();

        // 注册 CDJ 输入源
        DjLinkSourceInput djLinkSource = new DjLinkSourceInput();
        sourceInputManager.registerSource("djlink", djLinkSource);

        // 注册本地播放器输入源
        localEngine = new BasicLocalPlaybackEngine();
        InMemoryTrackRepository trackRepo = new InMemoryTrackRepository();
        LocalLibraryService libraryService = new LocalLibraryService(trackRepo, new BasicAudioAnalyzer());
        LocalSourceInput localSource = new LocalSourceInput(localEngine, libraryService);
        sourceInputManager.registerSource("local", localSource);

        // 默认使用 CDJ
        sourceInputManager.switchToSource("djlink");

        // 创建 LTC/MTC 驱动
        LtcDriver ltcDriver = new LtcDriver();
        MtcDriver mtcDriver = new MtcDriver();

        // 注册为时间码消费者和状态监听器
        timecodeCore.registerConsumer(ltcDriver);
        timecodeCore.addStateListener(ltcDriver);
        timecodeCore.registerConsumer(mtcDriver);
        timecodeCore.addStateListener(mtcDriver);

        // 注册所有驱动
        register(ltcDriver);
        register(mtcDriver);
        register(new AbletonLinkDriver());
        register(new TitanApiDriver());
        register(new Ma2BpmDriver());
        register(new ConsoleApiDriver());

        // 启动时间码核心
        timecodeCore.start();

        // 启动本地播放器状态推送定时器（100ms）
        startLocalPlayerTimer();
    }

    // 本地播放器引擎引用（用于配置应用）
    private BasicLocalPlaybackEngine localEngine;

    private java.util.concurrent.ScheduledExecutorService localPlayerTimer;

    private void startLocalPlayerTimer() {
        localPlayerTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "local-player-timer")
        );
        localPlayerTimer.scheduleAtFixedRate(() -> {
            if ("local".equals(activeSourceType)) {
                System.out.println("[SyncOutputManager] Local player timer tick, state=" + sourceState);
                onLocalPlayerState();
            }
        }, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        System.out.println("[SyncOutputManager] Local player timer started");
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

                // sourcePlayer 已统一到 sync.masterPlayer，TimecodeCore 从 SyncOutputManager 获取

                // 应用驱动配置
                for (Map.Entry<String, OutputDriver> e : drivers.entrySet()) {
                    Map<String, Object> cfg = sync.get(e.getKey()) instanceof Map
                        ? (Map<String, Object>) sync.get(e.getKey()) : Map.of();
                    boolean enabled = Boolean.TRUE.equals(cfg.get("enabled"));
                    try { e.getValue().stop(); } catch (Exception ignored) {}
                    if (enabled) {
                        try { e.getValue().start(cfg); } catch (Exception ignored) {}
                    }
                }

                // 应用本地播放器音频设备配置
                Map<String, Object> localPlayerCfg = sync.get("localPlayer") instanceof Map
                    ? (Map<String, Object>) sync.get("localPlayer") : Map.of();
                String audioDevice = localPlayerCfg.get("audioDevice") instanceof String
                    ? (String) localPlayerCfg.get("audioDevice") : "default";
                if (localEngine != null) {
                    String oldDevice = localEngine.getAudioDevice();
                    localEngine.setAudioDevice(audioDevice);
                    System.out.println("[SyncOutputManager] Local player audio device: " + oldDevice + " -> " + audioDevice);
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
        // 如果当前是本地播放器模式，跳过 CDJ 状态处理
        if ("local".equals(activeSourceType)) {
            return;
        }

        // 保存 player 列表
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

        // 构建派生状态
        Map<String, Object> derived = buildDerivedState();

        if (playersState != null) {
            Object players = playersState.get("players");
            if (players instanceof List) {
                List<?> list = (List<?>) players;
                Map<String, Object> chosen = null;

                Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
                Map<String, Object> sync = settings.get("sync") instanceof Map
                    ? (Map<String, Object>) settings.get("sync") : Map.of();
                String sourceMode = String.valueOf(sync.getOrDefault("sourceMode", "master"));
                int selectedPlayer = sync.get("masterPlayer") instanceof Number
                    ? ((Number) sync.get("masterPlayer")).intValue() : 1;

                // A) 手动模式
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

                // B) 跟随 master
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
                        if (Boolean.TRUE.equals(p.get("playing")) && Boolean.TRUE.equals(p.get("active"))) {
                            chosen = p; break;
                        }
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
                        nowSec = 0.0;
                        lastTimeSec = 0.0;
                    }

                    int beat = chosen.get("beat") instanceof Number ? ((Number) chosen.get("beat")).intValue() : -1;
                    boolean stoppedLike = !playing && (beat < 0 || nowSec <= 0.05);
                    if (stoppedLike) {
                        lastTimeSec = 0.0;
                    }

                    derived.put("masterTimeSec", stoppedLike ? 0.0 : lastTimeSec);
                    if (bpm instanceof Number) derived.put("masterBpm", ((Number) bpm).doubleValue());
                    if (pitch instanceof Number) derived.put("sourcePitchPct", ((Number) pitch).doubleValue());
                    derived.put("sourcePlayer", chosen.get("number"));
                    derived.put("sourceMode", sourceMode);
                    derived.put("sourcePlaying", playing);
                    derived.put("sourceActive", active);
                    if (ms instanceof Number) derived.put("currentTimeMs", ((Number) ms).longValue());
                    if (ms instanceof Number) derived.put("beatTimeMs", ((Number) ms).longValue());
                    derived.put("remainingTimeMs", chosen.get("remainingTimeMs"));
                    derived.put("playerId", chosen.get("number"));
                    derived.put("trackId", chosen.get("trackId"));
                    derived.put("rekordboxId", chosen.get("rekordboxId"));
                    derived.put("playing", playing);
                    if (bpm instanceof Number) derived.put("bpm", ((Number) bpm).doubleValue());
                    if (pitch instanceof Number) derived.put("pitch", ((Number) pitch).doubleValue());
                    String st = !active ? "OFFLINE" : (playing ? "PLAYING" : (beat < 0 || nowSec <= 0.05 ? "STOPPED" : "PAUSED"));
                    sourceState = st;
                    sourcePlayer = chosen.get("number") instanceof Number ? ((Number) chosen.get("number")).intValue() : null;
                    derived.put("sourceState", st);
                } else {
                    sourceState = "OFFLINE";
                    sourcePlayer = null;
                    lastTimeSec = 0.0;
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

        // 广播状态
        broadcastState(derived);
    }

    private double currentMasterBpm = 120.0;
    private boolean currentSourcePlaying = false;

    @SuppressWarnings("unchecked")
    private void broadcastState(Map<String, Object> derived) {
        // 保存当前状态用于 API 查询
        Object bpm = derived.get("masterBpm");
        if (bpm instanceof Number) {
            currentMasterBpm = ((Number) bpm).doubleValue();
        }
        Object playing = derived.get("sourcePlaying");
        if (playing instanceof Boolean) {
            currentSourcePlaying = (Boolean) playing;
        }

        // 传递给时间码核心（只执行一次事件检测）
        timecodeCore.update(derived);

        // 传递给其他驱动（LtcDriver/MtcDriver 不再重复处理播放器逻辑）
        for (Map.Entry<String, OutputDriver> e : drivers.entrySet()) {
            OutputDriver d = e.getValue();
            d.update(derived);
        }
    }

    private Map<String, Object> buildDerivedState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("masterTimeSec", lastTimeSec);
        state.put("rawTimeSec", lastTimeSec);
        state.put("masterBpm", 120.0);
        state.put("sourcePlaying", false);
        state.put("sourceActive", false);
        state.put("sourceState", "OFFLINE");
        state.put("semantic", new LinkedHashMap<>(lastSemantic));
        // FIX: 复制 players 列表，避免并发竞争导致列表被清空
        state.put("players", new ArrayList<>(lastPlayersList));
        return state;
    }

    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 驱动状态
        Map<String, Object> ds = new LinkedHashMap<>();
        for (OutputDriver d : drivers.values()) {
            ds.put(d.name(), d.status());
        }
        out.put("drivers", ds);

        // 源状态
        out.put("sourceState", sourceState);
        out.put("sourcePlayer", sourcePlayer != null ? sourcePlayer : 0);
        out.put("sourceType", activeSourceType);
        out.put("masterBpm", currentMasterBpm);
        out.put("sourcePlaying", currentSourcePlaying);

        // 时间码核心状态（独立）
        out.put("timecode", timecodeCore.getStatus());

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

    public synchronized void setTimecodeManualTestMode(boolean enabled) {
        timecodeCore.setManualTestMode(enabled);
    }

    public synchronized boolean isTimecodeManualTestMode() {
        return timecodeCore.isManualTestMode();
    }

    // ========== Source Input Manager 集成 ==========

    /**
     * 获取输入源管理器（用于本地播放器控制）
     */
    public SourceInputManager getSourceInputManager() {
        return sourceInputManager;
    }

    /**
     * 切换输入源（djlink / local）
     */
    public synchronized boolean switchSource(String sourceType) {
        if (!"djlink".equals(sourceType) && !"local".equals(sourceType)) {
            return false;
        }
        boolean success = sourceInputManager.switchToSource(sourceType);
        if (success) {
            this.activeSourceType = sourceType;
            System.out.println("[SyncOutputManager] Switched to source: " + sourceType);
        }
        return success;
    }

    /**
     * 获取当前激活的输入源类型
     */
    public String getActiveSourceType() {
        return activeSourceType;
    }

    /**
     * 从本地播放器输入源获取状态（当 local 模式时调用）
     */
    public void onLocalPlayerState() {
        SourceInput localSource = sourceInputManager.getSource("local");
        if (localSource == null || !"local".equals(activeSourceType)) {
            return;
        }

        String localState = localSource.getState();
        double localTimeSec = localSource.getSourceTimeSec();
        boolean localPlaying = "PLAYING".equals(localState);
        
        // 获取 PlaybackEngine 实例 ID 用于调试
        Object playbackEngine = null;
        if (localSource instanceof dbclient.input.LocalSourceInput) {
            try {
                java.lang.reflect.Method m = localSource.getClass().getMethod("getPlaybackEngine");
                playbackEngine = m.invoke(localSource);
            } catch (Exception e) {}
        }
        
        System.out.println("[SyncOutputManager] onLocalPlayerState: localSource=" + System.identityHashCode(localSource) + 
            ", PlaybackEngine=" + (playbackEngine != null ? System.identityHashCode(playbackEngine) : "null") +
            ", state=" + localState + ", timeSec=" + localTimeSec);

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("masterTimeSec", localTimeSec);
        derived.put("rawTimeSec", localTimeSec);
        derived.put("masterBpm", localSource.getSourceBpm());
        derived.put("sourcePlaying", localPlaying);
        derived.put("sourceActive", localSource.isOnline());
        derived.put("sourcePlayer", 1); // 本地播放器固定为 1
        derived.put("sourceMode", "local");
        derived.put("sourceState", localState);
        derived.put("players", List.of(Map.of(
            "number", 1,
            "playing", localPlaying,
            "active", localSource.isOnline(),
            "currentTimeMs", (long)(localTimeSec * 1000),
            "bpm", localSource.getSourceBpm(),
            "pitch", localSource.getSourcePitch()
        )));

        this.sourceState = localState;
        this.sourcePlayer = 1;
        this.lastTimeSec = localTimeSec;

        System.out.println("[SyncOutputManager] onLocalPlayerState: broadcast with sourceState=" + localState);
        broadcastState(derived);
    }
}
