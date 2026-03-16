package dbclient.media.player;

import dbclient.media.model.PlaybackStatus;
import dbclient.media.model.TrackInfo;

/**
 * 播放引擎接口
 * 允许替换不同播放引擎实现（Java Sound、JLayer、JavaFX Media 等）
 * 接口只使用标准 JDK 类型和我们的 DTO，不暴露开源库类型
 */
public interface PlaybackEngine {

    /**
     * 加载曲目
     *
     * @param track 曲目信息
     */
    void load(TrackInfo track);

    /**
     * 开始播放
     */
    void play();

    /**
     * 暂停播放
     */
    void pause();

    /**
     * 停止播放
     */
    void stop();

    /**
     * 跳转到指定位置
     *
     * @param positionMs 目标位置（毫秒）
     */
    void seek(long positionMs);

    /**
     * 获取当前播放状态
     *
     * @return PlaybackStatus（我们的 DTO）
     */
    PlaybackStatus getStatus();

    /**
     * 获取当前加载的曲目
     *
     * @return 曲目信息，未加载时返回 null
     */
    TrackInfo getCurrentTrack();

    /**
     * 关闭播放器，释放资源
     */
    void close();

    /**
     * 获取播放引擎名称
     */
    String getEngineName();
}
