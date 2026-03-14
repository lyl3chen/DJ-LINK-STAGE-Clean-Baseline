package dbclient.sync;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TimecodeTimeline - 统一时间推进核心
 * 
 * 职责：
 * - 恒速推进：本地单调时钟推进
 * - 事件重锚：明确事件发生时重新设置锚点
 * - 不做连续误差补偿
 * 
 * 推进公式：timelineSec = anchorTrackSec + (nowMonoNs - anchorMonoNs) / 1e9
 */
public class TimecodeTimeline {
    
    private static final TimecodeTimeline INSTANCE = new TimecodeTimeline();
    
    // 锚点信息
    private volatile double anchorTrackSec = 0.0;      // 锚点时的轨道时间（秒）
    private volatile long anchorMonoNs = 0;          // 锚点时的单调时钟（纳秒）
    
    // 当前状态
    private volatile PlayState playState = PlayState.STOPPED;
    private volatile int sourcePlayer = 0;
    private volatile String lastEvent = "init";
    
    // 事件类型
    public enum PlayState {
        STOPPED,   // 完全停止
        PAUSED,    // 暂停
        PLAYING    // 播放中
    }
    
    // 事件类型
    public enum Event {
        PLAY_START,    // 开始播放
        PAUSE,         // 暂停
        RESUME,        // 恢复播放
        HOT_CUE,       // 热键跳转
        TRACK_CHANGE,  // 换歌
        JUMP           // 明显跳变
    }
    
    public static TimecodeTimeline getInstance() {
        return INSTANCE;
    }
    
    private TimecodeTimeline() {}
    
    /**
     * 获取当前 timeline 时间（秒）
     * 基于本地单调时钟恒速推进
     */
    public double getTimelineSec() {
        PlayState state = playState;
        
        if (state == PlayState.STOPPED) {
            return 0.0;
        }
        
        long nowMonoNs = System.nanoTime();
        long elapsedNs = nowMonoNs - anchorMonoNs;
        double elapsedSec = elapsedNs / 1_000_000_000.0;
        
        return anchorTrackSec + elapsedSec;
    }
    
    /**
     * 获取当前播放状态
     */
    public PlayState getPlayState() {
        return playState;
    }
    
    /**
     * 获取当前 source player
     */
    public int getSourcePlayer() {
        return sourcePlayer;
    }
    
    /**
     * 获取最后事件
     */
    public String getLastEvent() {
        return lastEvent;
    }
    
    /**
     * 事件：开始播放（从停止或换歌后）
     * @param trackSec 轨道当前时间（秒）
     */
    public void onPlayStart(double trackSec) {
        reanchor(trackSec, Event.PLAY_START);
    }
    
    /**
     * 事件：暂停
     */
    public void onPause() {
        playState = PlayState.PAUSED;
        lastEvent = "pause";
    }
    
    /**
     * 事件：恢复播放（从暂停）
     * @param trackSec 轨道当前时间（秒）
     */
    public void onResume(double trackSec) {
        reanchor(trackSec, Event.RESUME);
    }
    
    /**
     * 事件：停止
     */
    public void onStop() {
        playState = PlayState.STOPPED;
        lastEvent = "stop";
    }
    
    /**
     * 事件：HOT CUE 跳转
     * @param trackSec 跳转后的轨道时间（秒）
     */
    public void onHotCue(double trackSec) {
        reanchor(trackSec, Event.HOT_CUE);
    }
    
    /**
     * 事件：换歌
     * @param trackSec 新歌的轨道时间（秒）
     */
    public void onTrackChange(double trackSec) {
        reanchor(trackSec, Event.TRACK_CHANGE);
    }
    
    /**
     * 事件：明显跳变（>2秒）
     * @param trackSec 跳变后的轨道时间（秒）
     */
    public void onJump(double trackSec) {
        reanchor(trackSec, Event.JUMP);
    }
    
    /**
     * 事件：source 变化
     * @param playerId 新的 player ID
     */
    public void onSourceChange(int playerId) {
        if (playerId != sourcePlayer) {
            sourcePlayer = playerId;
            lastEvent = "source_change:" + playerId;
        }
    }
    
    /**
     * 重置 timeline
     */
    public void reset() {
        anchorTrackSec = 0.0;
        anchorMonoNs = System.nanoTime();
        playState = PlayState.STOPPED;
        lastEvent = "reset";
    }
    
    /**
     * 执行重锚
     */
    private void reanchor(double trackSec, Event event) {
        anchorTrackSec = trackSec;
        anchorMonoNs = System.nanoTime();
        
        if (playState == PlayState.STOPPED || playState == PlayState.PAUSED) {
            playState = PlayState.PLAYING;
        }
        
        lastEvent = event.name().toLowerCase();
        
        System.out.println("[Timeline] REANCHOR: event=" + lastEvent + " trackSec=" + trackSec);
    }
    
    /**
     * 从外部状态更新 timeline
     * 由 SyncOutputManager 调用
     * @param playerId 当前 source player ID
     * @param currentTimeMs 当前轨道时间（毫秒）
     * @param playing 是否正在播放
     * @param active player 是否在线
     * @param trackId track ID（用于检测换歌）
     */
    public void updateFromSource(int playerId, long currentTimeMs, boolean playing, boolean active, String trackId) {
        PlayState oldState = playState;
        int oldPlayer = sourcePlayer;
        
        // 更新 source
        if (playerId != sourcePlayer) {
            sourcePlayer = playerId;
            lastEvent = "source_change:" + playerId;
            System.out.println("[Timeline] SOURCE_CHANGE: " + oldPlayer + " -> " + playerId);
        }
        
        // 无效 source
        if (playerId <= 0 || !active) {
            if (playState != PlayState.STOPPED) {
                System.out.println("[Timeline] STOP: no valid source, playerId=" + playerId + " active=" + active);
            }
            playState = PlayState.STOPPED;
            return;
        }
        
        double trackSec = currentTimeMs / 1000.0;
        
        // 检测跳变 (>2秒)
        if (playState == PlayState.PLAYING && playing) {
            double oldTimelineSec = getTimelineSec();
            if (Math.abs(trackSec - oldTimelineSec) > 2.0) {
                System.out.println("[Timeline] JUMP DETECTED: " + oldTimelineSec + " -> " + trackSec + " (diff=" + (trackSec - oldTimelineSec) + ")");
                onJump(trackSec);
                return;
            }
        }
        
        // 状态机
        switch (playState) {
            case STOPPED:
                if (playing) {
                    System.out.println("[Timeline] PLAY_START: trackSec=" + trackSec);
                    onPlayStart(trackSec);
                }
                break;
                
            case PAUSED:
                if (playing) {
                    System.out.println("[Timeline] RESUME: trackSec=" + trackSec);
                    onResume(trackSec);
                }
                break;
                
            case PLAYING:
                if (!playing) {
                    System.out.println("[Timeline] PAUSE: trackSec=" + trackSec);
                    onPause();
                }
                break;
        }
    }
}
