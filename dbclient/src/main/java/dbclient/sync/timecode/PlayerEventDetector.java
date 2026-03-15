package dbclient.sync.timecode;

import java.util.*;

/**
 * PlayerEventDetector - 播放器事件检测器
 * 
 * 检测应触发重锚的事件：
 * - Play/Pause/Stop 状态变化
 * - 时间明显跳变（seek/hot cue）
 * - 切歌
 * - 累积漂移过大
 */
public class PlayerEventDetector {
    
    // 阈值配置
    private static final long JUMP_THRESHOLD_FRAMES = 10;      // 跳变阈值：10帧（0.4秒）
    private static final long DRIFT_THRESHOLD_FRAMES = 125;    // 漂移阈值：125帧（5秒）
    
    // 历史记录
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
        
        String currentState = normalizeState(state.state, state.playing);
        long currentFrame = (long) (state.timeSec * TimecodeCore.FRAME_RATE);
        
        // 1. 状态变化检测
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
        
        // 3. 时间跳变检测（seek/hot cue）
        // 只在 PLAYING 状态时检测跳变
        if ("PLAYING".equals(currentState)) {
            long frameDiff = Math.abs(currentFrame - expectedFrame);
            
            if (frameDiff > JUMP_THRESHOLD_FRAMES) {
                // 明显跳变，重锚
                driftAccumulator = 0;  // 重置漂移累积
                updateHistory(currentState, currentFrame, trackId);
                return PlayerEvent.TIME_JUMPED;
            } else {
                // 小漂移，累积但不重锚
                driftAccumulator += frameDiff;
            }
        }
        
        // 4. 累积漂移过大检测
        if (driftAccumulator > DRIFT_THRESHOLD_FRAMES) {
            driftAccumulator = 0;  // 重置
            updateHistory(currentState, currentFrame, trackId);
            return PlayerEvent.DRIFT_TOO_LARGE;
        }
        
        updateHistory(currentState, currentFrame, trackId);
        return PlayerEvent.NONE;
    }
    
    /**
     * 标准化状态
     */
    private String normalizeState(String rawState, boolean playing) {
        if (rawState == null) return "STOPPED";
        
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
    
    /**
     * 检测状态转换事件
     */
    private PlayerEvent detectStateTransition(String from, String to) {
        // STOPPED → PLAYING
        if ("STOPPED".equals(from) && "PLAYING".equals(to)) {
            return PlayerEvent.PLAY_STARTED;
        }
        
        // PAUSED → PLAYING
        if ("PAUSED".equals(from) && "PLAYING".equals(to)) {
            return PlayerEvent.RESUMED;
        }
        
        // PLAYING → PAUSED
        if ("PLAYING".equals(from) && "PAUSED".equals(to)) {
            return PlayerEvent.PAUSED;
        }
        
        // PLAYING → STOPPED
        if ("PLAYING".equals(from) && "STOPPED".equals(to)) {
            return PlayerEvent.STOPPED;
        }
        
        // PAUSED → STOPPED
        if ("PAUSED".equals(from) && "STOPPED".equals(to)) {
            return PlayerEvent.STOPPED;
        }
        
        return PlayerEvent.NONE;
    }
    
    private void updateHistory(String state, long frame, String trackId) {
        lastState = state;
        lastFrame = frame;
        lastTrackId = trackId;
    }
    
    /**
     * 播放器状态数据类
     */
    public static class PlayerState {
        public String state;
        public boolean playing;
        public double timeSec;
        public String trackId;
    }
}
