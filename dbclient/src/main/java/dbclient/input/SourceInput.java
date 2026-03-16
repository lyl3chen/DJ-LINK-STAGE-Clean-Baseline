package dbclient.input;

import dbclient.media.model.TrackInfo;

/**
 * 输入源接口
 * 统一抽象 DJ Link、本地播放器、手动测试等各种时间码来源
 * 接口只使用标准 JDK 类型和我们的 DTO，不暴露开源库类型
 */
public interface SourceInput {

    /**
     * 获取源类型标识
     *
     * @return "djlink" | "local" | "manual" | 其他自定义类型
     */
    String getType();

    /**
     * 获取源名称（用于显示）
     */
    String getDisplayName();

    /**
     * 获取当前播放状态
     *
     * @return "PLAYING" | "PAUSED" | "STOPPED" | "OFFLINE"
     */
    String getState();

    /**
     * 是否在线/可用
     */
    boolean isOnline();

    /**
     * 获取当前播放时间（秒）
     */
    double getSourceTimeSec();

    /**
     * 获取当前播放时间（毫秒）
     */
    default long getSourceTimeMs() {
        return (long) (getSourceTimeSec() * 1000);
    }

    /**
     * 获取源帧率（通常为 25.0）
     */
    double getSourceFrameRate();

    /**
     * 获取当前帧号
     */
    default long getSourceFrame() {
        return (long) (getSourceTimeSec() * getSourceFrameRate());
    }

    /**
     * 获取当前 BPM
     */
    double getSourceBpm();

    /**
     * 获取当前 pitch 调整（1.0 = 正常速度）
     */
    double getSourcePitch();

    /**
     * 获取当前曲目信息
     *
     * @return 曲目信息，无曲目时返回 null
     */
    TrackInfo getCurrentTrack();

    /**
     * 获取源播放器编号
     * DJ Link 模式下为 1-4，本地播放器可固定为 1
     */
    int getSourcePlayerNumber();

    /**
     * 获取 master BPM（用于同步）
     */
    default double getMasterBpm() {
        return getSourceBpm() * getSourcePitch();
    }

    /**
     * 是否处于播放状态
     */
    default boolean isPlaying() {
        return "PLAYING".equals(getState());
    }

    /**
     * 是否处于暂停状态
     */
    default boolean isPaused() {
        return "PAUSED".equals(getState());
    }

    /**
     * 是否处于停止状态
     */
    default boolean isStopped() {
        return "STOPPED".equals(getState());
    }
}
