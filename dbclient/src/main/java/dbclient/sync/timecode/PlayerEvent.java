package dbclient.sync.timecode;

/**
 * PlayerEvent - 播放器事件类型
 */
public enum PlayerEvent {
    NONE,           // 无事件，正常播放
    PLAY_STARTED,   // 从停止开始播放
    RESUMED,        // 从暂停恢复
    PAUSED,         // 暂停
    STOPPED,        // 停止
    TIME_JUMPED,    // 时间明显跳变（seek/hot cue）
    TRACK_CHANGED,  // 切歌
    DRIFT_TOO_LARGE // 累积漂移过大
}
