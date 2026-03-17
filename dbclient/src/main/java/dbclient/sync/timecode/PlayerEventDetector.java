package dbclient.sync.timecode;

import java.util.*;

/**
 * PlayerEventDetector - 播放器事件检测器 (FINAL - 已稳定)
 *
 * 【职责】
 * 检测应触发重锚的事件：状态变化、跳变、切歌、漂移过大
 *
 * 【关键阈值】
 * - JUMP_THRESHOLD: 10 帧 (0.4秒) - 超过视为跳变/seek
 * - DRIFT_THRESHOLD: 125 帧 (5秒) - 累积漂移过大触发重锚
 *
 * 【注意】
 * - 本类内部维护历史状态 (lastState, lastFrame, lastTrackId)
 * - 禁止外部重置历史状态
 * - normalizeState() 处理 rawState 为 null 的情况
 */
public class PlayerEventDetector {

    private static final long JUMP_THRESHOLD_FRAMES = 10;      // 0.4秒
    private static final long DRIFT_THRESHOLD_FRAMES = 125;    // 5秒

    // 历史记录（事件检测依赖这些状态）
    private String lastState = "STOPPED";
    private String lastTrackId = "";
    private long lastFrame = 0;
    private long driftAccumulator = 0;

    /**
     * 检测播放器事件
     *
     * @param state 当前播放器状态
     * @param expectedFrame 本地线性推进的预期帧
     * @param trackId 当前曲目ID
     * @return 检测到的事件类型
     */
    public PlayerEvent detect(PlayerState state, long expectedFrame, String trackId) {
        if (state == null) return PlayerEvent.NONE;

        String currentState = normalizeState(state.state, state.playing, state.timeSec);
        long currentFrame = (long) (state.timeSec * TimecodeCore.FRAME_RATE);

        // 1. 状态变化检测（最高优先级）
        if (!currentState.equals(lastState)) {
            PlayerEvent event = detectStateTransition(lastState, currentState);
            if (event != PlayerEvent.NONE) {
                updateHistory(currentState, currentFrame, trackId);
                return event;
            }
        }

        // 2. 切歌检测
        if (!trackId.equals(lastTrackId) && !trackId.isEmpty()) {
            updateHistory(currentState, currentFrame, trackId);
            return PlayerEvent.TRACK_CHANGED;
        }

        // 3. 时间跳变检测（只在 PLAYING 时）
        if ("PLAYING".equals(currentState)) {
            long frameDiff = Math.abs(currentFrame - expectedFrame);

            if (frameDiff > JUMP_THRESHOLD_FRAMES) {
                driftAccumulator = 0;  // 重置漂移累积
                updateHistory(currentState, currentFrame, trackId);
                return PlayerEvent.TIME_JUMPED;
            } else {
                driftAccumulator += frameDiff;
            }
        }

        // 4. 累积漂移过大检测
        if (driftAccumulator > DRIFT_THRESHOLD_FRAMES) {
            driftAccumulator = 0;
            updateHistory(currentState, currentFrame, trackId);
            return PlayerEvent.DRIFT_TOO_LARGE;
        }

        updateHistory(currentState, currentFrame, trackId);
        return PlayerEvent.NONE;
    }

    /**
     * 标准化状态
     * 【关键逻辑】rawState 为 null 时，根据 playing + timeSec 推断
     */
    private String normalizeState(String rawState, boolean playing, double timeSec) {
        if (rawState == null || rawState.isEmpty()) {
            if (playing) return "PLAYING";
            else if (timeSec > 0.05) return "PAUSED";
            else return "STOPPED";
        }

        switch (rawState.toUpperCase()) {
            case "PLAYING":
                return playing ? "PLAYING" : "PAUSED";
            case "PAUSED":
                return "PAUSED";
            case "STOPPED":
            case "OFFLINE":
                return "STOPPED";
            default:
                return playing ? "PLAYING" : "STOPPED";
        }
    }

    private PlayerEvent detectStateTransition(String from, String to) {
        if ("STOPPED".equals(from) && "PLAYING".equals(to)) return PlayerEvent.PLAY_STARTED;
        if ("PAUSED".equals(from) && "PLAYING".equals(to)) return PlayerEvent.RESUMED;
        if ("PLAYING".equals(from) && "PAUSED".equals(to)) return PlayerEvent.PAUSED;
        if ("PLAYING".equals(from) && "STOPPED".equals(to)) return PlayerEvent.STOPPED;
        if ("PAUSED".equals(from) && "STOPPED".equals(to)) return PlayerEvent.STOPPED;
        return PlayerEvent.NONE;
    }

    private void updateHistory(String state, long frame, String trackId) {
        lastState = state;
        lastFrame = frame;
        lastTrackId = trackId;
    }

    public static class PlayerState {
        public String state;      // 可能为 null
        public boolean playing;
        public double timeSec;
        public String trackId;
    }
}
