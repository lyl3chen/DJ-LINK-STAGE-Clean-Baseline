package dbclient.sync.timecode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * TimecodeCore - 共享时间码核心 (FINAL - 已稳定，勿改动架构)
 *
 * 【关键架构 - 禁止误改】
 * 1. sourcePlayer 由 SyncOutputManager 统一提供，本层不维护独立 source 字段
 * 2. update(state) 只用于事件检测和重锚，不作为输出节奏
 * 3. 独立线程以 25fps 均匀推送，通过 onFrame() 回调
 * 4. 事件驱动重锚：PLAY_STARTED, JUMPED, PAUSED, STOPPED
 * 5. 正常播放期间本地线性推进，不实时贴合 CDJ
 *
 * 【状态流转】
 * STOPPED -> PLAY_STARTED -> PLAYING --(JUMPED)--> PLAYING
 *                              |
 *                              +--(PAUSED)--> PAUSED
 *                              |
 *                              +--(STOPPED)--> STOPPED
 */
public class TimecodeCore implements Runnable {

    public static final double FRAME_RATE = 25.0;
    public static final long FRAME_INTERVAL_MS = 40;  // 1000/25

    // 本地时钟锚点（事件发生时重置，PLAYING 期间不变）
    private volatile long anchorTimeNs = 0;     // System.nanoTime()
    private volatile long anchorFrame = 0;      // 当前锚点帧号
    private volatile String currentState = "STOPPED";  // STOPPED/PAUSED/PLAYING

    // 事件检测器（历史状态在内部维护，禁止外部重置）
    private final PlayerEventDetector detector = new PlayerEventDetector();
    private volatile String lastTrackId = "";

    // 手动测试模式（脱离 CDJ，固定从 00:00:00.00 推进）
    private volatile boolean manualTestMode = false;

    // 运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread outputThread;

    // 消费者列表（LTC/MTC，顺序注册顺序回调）
    private final List<TimecodeConsumer> consumers = new CopyOnWriteArrayList<>();

    // 【已废弃】源变更监听器，保留仅作兼容性，不再使用
    public interface SourceChangeListener {
        void onSourceChanged(int newPlayer);
    }

    public void registerConsumer(TimecodeConsumer consumer) {
        if (consumer != null) consumers.add(consumer);
    }

    /**
     * 设置手动测试模式
     * 开启后：从 00:00:00.00 固定推进，不受 CDJ 影响
     */
    public void setManualTestMode(boolean enabled) {
        if (manualTestMode == enabled) return;

        String oldState = currentState;
        manualTestMode = enabled;

        if (enabled) {
            currentState = "PLAYING";
            anchorFrame = 0;
            anchorTimeNs = System.nanoTime();
        } else {
            currentState = "STOPPED";
            anchorFrame = 0;
        }

        if (!currentState.equals(oldState)) {
            notifyStateChange(oldState, currentState);
        }
    }

    public boolean isManualTestMode() {
        return manualTestMode;
    }

    /**
     * 接收播放器状态，用于事件检测和重锚
     * 【关键】本方法只更新内部状态机，不直接输出时间码
     * 输出由独立线程通过 run() -> onFrame() 驱动
     *
     * @param state 必须包含：sourcePlayer, players[], sourceState
     */
    public void update(Map<String, Object> state) {
        if (state == null || manualTestMode) return;

        int currentSourcePlayer = state.get("sourcePlayer") instanceof Number
            ? ((Number) state.get("sourcePlayer")).intValue() : 1;

        PlayerEventDetector.PlayerState ps = extractPlayerState(state, currentSourcePlayer);
        if (ps == null) return;

        // 计算预期帧（基于当前锚点和流逝时间）
        long expectedFrame = anchorFrame;
        if ("PLAYING".equals(currentState)) {
            long elapsedNs = System.nanoTime() - anchorTimeNs;
            long elapsedFrames = (long) (elapsedNs * FRAME_RATE / 1_000_000_000.0);
            expectedFrame = anchorFrame + elapsedFrames;
        }

        // 事件检测（核心逻辑）
        PlayerEvent event = detector.detect(ps, expectedFrame, lastTrackId);

        if (event != PlayerEvent.NONE) {
            handleEvent(event, ps);
        }

        lastTrackId = ps.trackId;
    }

    /**
     * 启动时间码核心（独立线程 25fps 输出）
     */
    public void start() {
        if (running.get()) return;
        running.set(true);
        outputThread = new Thread(this, "timecode-core");
        outputThread.start();
    }

    /**
     * 停止时间码核心
     */
    public void stop() {
        if (!running.get()) return;
        running.set(false);
        if (outputThread != null) {
            outputThread.interrupt();
            try { outputThread.join(100); } catch (InterruptedException ignored) {}
            outputThread = null;
        }
    }

    /**
     * 独立线程：均匀输出节奏（25fps）
     * 【关键】每次循环向所有消费者推送当前帧
     */
    @Override
    public void run() {
        long nextFrameTime = System.currentTimeMillis();

        while (running.get()) {
            try {
                long currentFrame = getCurrentFrame();

                for (TimecodeConsumer consumer : consumers) {
                    try { consumer.onFrame(currentFrame); }
                    catch (Exception e) { /* 忽略单个消费者错误 */ }
                }

                nextFrameTime += FRAME_INTERVAL_MS;
                long sleepTime = nextFrameTime - System.currentTimeMillis();
                if (sleepTime > 0) Thread.sleep(sleepTime);

            } catch (InterruptedException e) { break; }
            catch (Exception e) { /* 忽略错误，继续运行 */ }
        }
    }

    /**
     * 获取当前帧（基于本地线性推进）
     * 【公式】PLAYING: anchorFrame + elapsedFramesFromAnchor
     */
    public long getCurrentFrame() {
        switch (currentState) {
            case "PLAYING":
                long elapsedNs = System.nanoTime() - anchorTimeNs;
                long elapsedFrames = (long) (elapsedNs * FRAME_RATE / 1_000_000_000.0);
                return anchorFrame + elapsedFrames;
            case "PAUSED":
                return anchorFrame;
            case "STOPPED":
            default:
                return 0;
        }
    }

    /**
     * 处理事件，更新锚点和状态
     * 【关键】所有状态变更和重锚逻辑集中于此
     */
    private void handleEvent(PlayerEvent event, PlayerEventDetector.PlayerState ps) {
        long newFrame = (long) (ps.timeSec * FRAME_RATE);
        String oldState = currentState;

        switch (event) {
            case PLAY_STARTED:
            case RESUMED:
                currentState = "PLAYING";
                anchorFrame = newFrame;
                anchorTimeNs = System.nanoTime();
                break;

            case PAUSED:
                if (!"PAUSED".equals(currentState)) {
                    currentState = "PAUSED";
                    if (newFrame > 0) anchorFrame = newFrame;
                }
                break;

            case STOPPED:
                currentState = "STOPPED";
                anchorFrame = 0;
                break;

            case TIME_JUMPED:
            case TRACK_CHANGED:
            case DRIFT_TOO_LARGE:
                // 重锚：保持当前状态，更新锚点
                if ("PLAYING".equals(currentState) && ps.playing) {
                    anchorFrame = newFrame;
                    anchorTimeNs = System.nanoTime();
                } else if ("PAUSED".equals(currentState) && newFrame > 0) {
                    anchorFrame = newFrame;
                }
                break;
        }

        if (!currentState.equals(oldState)) {
            notifyStateChange(oldState, currentState);
        }
    }

    /**
     * 通知驱动状态变化（用于 LTC/MTC 状态机同步）
     */
    private void notifyStateChange(String from, String to) {
        for (TimecodeConsumer c : consumers) {
            if (c instanceof TimecodeStateListener) {
                try {
                    ((TimecodeStateListener) c).onStateChange(from, to, anchorFrame, anchorTimeNs);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 从 players 列表提取指定 player 的状态
     */
    private PlayerEventDetector.PlayerState extractPlayerState(Map<String, Object> state, int playerNum) {
        Object playersObj = state.get("players");
        if (!(playersObj instanceof List)) return null;

        for (Object p : (List<?>) playersObj) {
            if (!(p instanceof Map)) continue;
            Map<?, ?> player = (Map<?, ?>) p;
            Object num = player.get("number");
            if (num instanceof Number && ((Number) num).intValue() == playerNum) {
                PlayerEventDetector.PlayerState ps = new PlayerEventDetector.PlayerState();
                Object stateObj = player.get("state");
                ps.state = (stateObj != null) ? String.valueOf(stateObj) : null;
                ps.playing = Boolean.TRUE.equals(player.get("playing"));
                ps.timeSec = player.get("currentTimeMs") instanceof Number
                    ? ((Number) player.get("currentTimeMs")).doubleValue() / 1000.0
                    : 0;
                ps.trackId = String.valueOf(player.get("trackId") != null ? player.get("trackId") : "");
                return ps;
            }
        }
        return null;
    }

    /**
     * 获取当前状态（用于 API 查询）
     */
    public Map<String, Object> getStatus() {
        return Map.of(
            "state", currentState,
            "anchorFrame", anchorFrame,
            "currentFrame", getCurrentFrame(),
            "frameRate", FRAME_RATE,
            "running", running.get(),
            "consumerCount", consumers.size(),
            "manualTestMode", manualTestMode
        );
    }

    // 【已废弃】状态监听器接口，仅保留兼容性
    public interface TimecodeStateListener {
        void onStateChange(String fromState, String toState, long anchorFrame, long anchorTimeNs);
    }

    // 状态监听器列表
    private final List<TimecodeStateListener> stateListeners = new CopyOnWriteArrayList<>();

    public void addStateListener(TimecodeStateListener listener) {
        if (listener != null) stateListeners.add(listener);
    }
}
