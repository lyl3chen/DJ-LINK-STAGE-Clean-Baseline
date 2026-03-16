package dbclient.input;

import dbclient.media.model.PlaybackStatus;
import dbclient.media.model.TrackInfo;
import dbclient.media.player.BasicLocalPlaybackEngine;
import dbclient.media.player.PlaybackEngine;

/**
 * 本地播放器输入源适配器
 * 接入 BasicLocalPlaybackEngine，不再是纯 stub
 */
public class LocalSourceInput implements SourceInput {

    private final PlaybackEngine playbackEngine;
    private volatile TrackInfo currentTrack;

    public LocalSourceInput() {
        this.playbackEngine = new BasicLocalPlaybackEngine();
    }

    // 用于测试的构造函数（允许注入 mock engine）
    public LocalSourceInput(PlaybackEngine engine) {
        this.playbackEngine = engine;
    }

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public String getDisplayName() {
        return "Local Player";
    }

    @Override
    public String getState() {
        PlaybackStatus status = playbackEngine.getStatus();
        if (status == null || status.getState() == null) {
            return "STOPPED";
        }
        return status.getState().name();
    }

    @Override
    public boolean isOnline() {
        return true; // 本地播放器始终在线
    }

    @Override
    public double getSourceTimeSec() {
        PlaybackStatus status = playbackEngine.getStatus();
        if (status == null) {
            return 0.0;
        }
        return status.getPositionMs() / 1000.0;
    }

    @Override
    public double getSourceFrameRate() {
        return 25.0;
    }

    @Override
    public double getSourceBpm() {
        // 第一版：从 PlaybackStatus 获取 effectiveBpm
        // 如果分析器还没实现，effectiveBpm 可能是默认值
        PlaybackStatus status = playbackEngine.getStatus();
        if (status == null) {
            return 0.0;
        }

        // 如果有 effectiveBpm 就用，否则返回 0 表示未知
        double bpm = status.getEffectiveBpm();
        return bpm > 0 ? bpm : 0.0;
    }

    @Override
    public double getSourcePitch() {
        PlaybackStatus status = playbackEngine.getStatus();
        if (status == null) {
            return 1.0;
        }
        return status.getPitch();
    }

    @Override
    public TrackInfo getCurrentTrack() {
        // 优先从 PlaybackEngine 获取
        TrackInfo track = playbackEngine.getCurrentTrack();
        if (track != null) {
            return track;
        }
        // 备用：返回缓存的 currentTrack
        return currentTrack;
    }

    @Override
    public int getSourcePlayerNumber() {
        return 1; // 本地播放器固定为 1
    }

    // ====== 播放控制方法（供上层调用）======

    public void load(TrackInfo track) {
        this.currentTrack = track;
        playbackEngine.load(track);
    }

    public void play() {
        playbackEngine.play();
    }

    public void pause() {
        playbackEngine.pause();
    }

    public void stop() {
        playbackEngine.stop();
    }

    public void seek(long positionMs) {
        playbackEngine.seek(positionMs);
    }

    public PlaybackStatus getPlaybackStatus() {
        return playbackEngine.getStatus();
    }

    /**
     * 关闭播放器，释放资源
     */
    public void close() {
        playbackEngine.close();
    }

    /**
     * 获取底层 PlaybackEngine（用于测试或高级操作）
     */
    public PlaybackEngine getPlaybackEngine() {
        return playbackEngine;
    }
}
