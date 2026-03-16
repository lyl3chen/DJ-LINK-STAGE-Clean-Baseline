package dbclient.sync.timecode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * TimecodeCore - 共享时间码核心
 *
 * 职责：
 * - 管理手动选择的播放源
 * - 播放器事件检测
 * - 本地单调时钟线性推进
 * - 时间码重锚（re-anchor）
 * - 向消费者输出当前线性时间
 *
 * 原则：
 * - update(state) 只用于事件检测，不作为输出节奏
 * - 独立线程驱动均匀输出（25fps）
 * - 正常播放期间不实时贴合 CDJ，仅事件重锚
 */
public class TimecodeCore implements Runnable {

    public static final double FRAME_RATE = 25.0;
    public static final long FRAME_INTERVAL_MS = 40;  // 1000/25

    // 配置
    private volatile int sourcePlayer = 1;  // 手动选择的播放源（1-4）

    // 本地时钟状态
    private volatile long anchorTimeNs = 0;     // 单调时间锚点（System.nanoTime）
    private volatile long anchorFrame = 0;      // 帧号锚点
    private volatile String currentState = "STOPPED";  // STOPPED/PAUSED/PLAYING

    // 事件检测
    private final PlayerEventDetector detector = new PlayerEventDetector();
    private volatile String lastTrackId = "";
    private volatile long lastUpdateTime = 0;

    // 手动测试模式（脱离播放源，固定线性推进）
    private volatile boolean manualTestMode = false;

    // 运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread outputThread;

    // 消费者列表（LtcDriver, MtcDriver）
    private final List<TimecodeConsumer> consumers = new CopyOnWriteArrayList<>();

    // 源变更监听器
    public interface SourceChangeListener {
        void onSourceChanged(int newPlayer);
    }
    private final List<SourceChangeListener> sourceListeners = new CopyOnWriteArrayList<>();

    public void addSourceChangeListener(SourceChangeListener listener) {
        if (listener != null) sourceListeners.add(listener);
    }

    public void setSourcePlayer(int player) {
        int newPlayer = Math.max(1, Math.min(4, player));
        if (newPlayer != this.sourcePlayer) {
            this.sourcePlayer = newPlayer;
            for (SourceChangeListener l : sourceListeners) {
                try { l.onSourceChanged(newPlayer); } catch (Exception ignored) {}
            }
        }
    }

    public int getSourcePlayer() {
        return sourcePlayer;
    }

    /**
     * 注册消费者（LTC/MTC 驱动）
     */
    public void registerConsumer(TimecodeConsumer consumer) {
        if (consumer != null) {
            consumers.add(consumer);
        }
    }

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

        // 通知状态变化
        if (!currentState.equals(oldState)) {
            notifyStateChange(oldState, currentState);
        }
    }

    public boolean isManualTestMode() {
        return manualTestMode;
    }

    /**
     * 接收播放器状态，用于事件检测和重锚判断
     * 注意：此方法只更新状态，不作为输出节奏
     * sourcePlayer 从 state 中读取（由 SyncOutputManager 统一提供）
     */
    public void update(Map<String, Object> state) {
        if (state == null) return;
        if (manualTestMode) return;

        // 从 SyncOutputManager 获取当前 sourcePlayer
        int currentSourcePlayer = state.get("sourcePlayer") instanceof Number 
            ? ((Number) state.get("sourcePlayer")).intValue() : this.sourcePlayer;

        // 提取选定播放器的状态
        PlayerEventDetector.PlayerState ps = extractPlayerState(state, currentSourcePlayer);
        if (ps == null) return;

        // 计算当前预期帧（本地线性推进）
        long expectedFrame = anchorFrame;
        if ("PLAYING".equals(currentState)) {
            long elapsedNs = System.nanoTime() - anchorTimeNs;
            long elapsedFrames = (long) (elapsedNs * FRAME_RATE / 1_000_000_000.0);
            expectedFrame = anchorFrame + elapsedFrames;
        }

        // 事件检测
        PlayerEvent event = detector.detect(ps, expectedFrame, lastTrackId);

        // 处理事件
        if (event != PlayerEvent.NONE) {
            handleEvent(event, ps);
        }

        // 更新记录
        lastTrackId = ps.trackId;
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * 启动时间码核心
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
            try {
                outputThread.join(100);
            } catch (InterruptedException ignored) {}
            outputThread = null;
        }
    }

    /**
     * 独立线程：均匀输出节奏（25fps）
     */
    @Override
    public void run() {
        long nextFrameTime = System.currentTimeMillis();

        while (running.get()) {
            try {
                long currentFrame = getCurrentFrame();

                // 推送给所有消费者
                for (TimecodeConsumer consumer : consumers) {
                    try {
                        consumer.onFrame(currentFrame);
                    } catch (Exception e) {
                        System.err.println("[TimecodeCore] Consumer error: " + e.getMessage());
                    }
                }

                // 计算下一帧时间
                nextFrameTime += FRAME_INTERVAL_MS;
                long sleepTime = nextFrameTime - System.currentTimeMillis();

                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                System.err.println("[TimecodeCore] Error: " + e.getMessage());
            }
        }
    }

    /**
     * 获取当前帧（本地线性推进）
     */
    public long getCurrentFrame() {
        switch (currentState) {
            case "PLAYING":
                long elapsedNs = System.nanoTime() - anchorTimeNs;
                long elapsedFrames = (long) (elapsedNs * FRAME_RATE / 1_000_000_000.0);
                return anchorFrame + elapsedFrames;
            case "PAUSED":
                return anchorFrame;  // 冻结
            case "STOPPED":
            default:
                return 0;  // 归零
        }
    }

    /**
     * 获取当前状态
     */
    public Map<String, Object> getStatus() {
        return Map.of(
            "tcSourcePlayer", sourcePlayer,
            "state", currentState,
            "anchorFrame", anchorFrame,
            "currentFrame", getCurrentFrame(),
            "frameRate", FRAME_RATE,
            "running", running.get(),
            "consumerCount", consumers.size(),
            "manualTestMode", manualTestMode
        );
    }

    // ============ 私有方法 ============

    private PlayerEventDetector.PlayerState extractPlayerState(Map<String, Object> state, int playerNum) {
        Object playersObj = state.get("players");
        if (!(playersObj instanceof List)) return null;

        List<?> players = (List<?>) playersObj;
        for (Object p : players) {
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
                // 只有首次进入 PAUSED 才冻结，避免边界抖动反复更新
                if (!"PAUSED".equals(currentState)) {
                    currentState = "PAUSED";
                    if (newFrame > 0) {
                        anchorFrame = newFrame;
                    }
                }
                break;

            case STOPPED:
                currentState = "STOPPED";
                anchorFrame = 0;
                break;

            case TIME_JUMPED:
            case TRACK_CHANGED:
            case DRIFT_TOO_LARGE:
                // 重锚到当前时间
                if ("PLAYING".equals(currentState) && ps.playing) {
                    currentState = "PLAYING";
                    anchorFrame = newFrame;
                    anchorTimeNs = System.nanoTime();
                } else if ("PAUSED".equals(currentState)) {
                    // PAUSED 状态下只有 newFrame > 0 才更新，避免 timeMs 为 null 导致 frame=0
                    if (newFrame > 0) {
                        anchorFrame = newFrame;
                    }
                } else {
                    currentState = "STOPPED";
                    anchorFrame = 0;
                }
                break;
        }

        // 通知驱动状态变化
        if (!currentState.equals(oldState)) {
            notifyStateChange(oldState, currentState);
        }
    }

    private void notifyStateChange(String from, String to) {
        for (TimecodeStateListener listener : stateListeners) {
            try {
                listener.onStateChange(from, to, anchorFrame, anchorTimeNs);
            } catch (Exception e) {
                System.err.println("[TimecodeCore] State listener error: " + e.getMessage());
            }
        }
    }

    public interface TimecodeStateListener {
        void onStateChange(String fromState, String toState, long anchorFrame, long anchorTimeNs);
    }

    private final List<TimecodeStateListener> stateListeners = new CopyOnWriteArrayList<>();

    public void addStateListener(TimecodeStateListener listener) {
        if (listener != null) stateListeners.add(listener);
    }

    public void removeStateListener(TimecodeStateListener listener) {
        stateListeners.remove(listener);
    }
}
