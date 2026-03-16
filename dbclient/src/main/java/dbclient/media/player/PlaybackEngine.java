package dbclient.media.player;

import java.io.IOException;

/**
 * 播放引擎接口
 * 允许替换不同播放引擎实现（Java Sound、JLayer、JavaFX Media 等）
 * 接口只使用标准 JDK 类型，不暴露开源库类型
 */
public interface PlaybackEngine {

    /**
     * 打开音频文件
     *
     * @param filePath 文件绝对路径
     * @throws IOException 打开失败时抛出
     */
    void open(String filePath) throws IOException;

    /**
     * 开始播放
     */
    void start();

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
     * 设置播放速度（变速不变调）
     *
     * @param speed 速度倍数，1.0 = 正常速度
     */
    void setPlaybackSpeed(double speed);

    /**
     * 获取当前播放位置（毫秒）
     *
     * @return 当前位置（毫秒）
     */
    long getPositionMs();

    /**
     * 是否正在播放
     */
    boolean isPlaying();

    /**
     * 是否已暂停
     */
    boolean isPaused();

    /**
     * 获取当前播放速度
     */
    double getPlaybackSpeed();

    /**
     * 关闭播放器，释放资源
     */
    void close();

    /**
     * 获取播放引擎名称
     */
    String getEngineName();
}
