package dbclient.input;

import dbclient.media.library.LocalLibraryService;
import dbclient.media.model.AnalysisResult;
import dbclient.media.model.PlaybackStatus;
import dbclient.media.model.TrackInfo;
import dbclient.media.player.PlaybackEngine;

import java.util.Optional;

/**
 * 本地播放器输入源适配器
 * 接入 PlaybackEngine 和 LocalLibraryService
 */
/**
 * LocalSourceInput = PlaybackEngine 到统一 SourceInput 语义的适配层。
 *
 * 负责：把本地播放器状态映射为 sourceState/sourceTime/sourceBpm。
 * 不负责：时间码推进、输出驱动控制、source 切换策略。
 */
public class LocalSourceInput implements SourceInput {

    private final PlaybackEngine playbackEngine;
    private final LocalLibraryService libraryService;

    public LocalSourceInput(PlaybackEngine playbackEngine, LocalLibraryService libraryService) {
        this.playbackEngine = playbackEngine;
        this.libraryService = libraryService;
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
        // 策略：优先从 PlaybackStatus 获取，其次从曲库分析结果获取
        PlaybackStatus status = playbackEngine.getStatus();
        if (status != null && status.getEffectiveBpm() > 0) {
            return status.getEffectiveBpm();
        }

        // 从曲库分析结果获取
        String trackId = getCurrentTrackId();
        if (trackId != null && libraryService != null) {
            Optional<AnalysisResult> analysis = libraryService.getAnalysis(trackId);
            if (analysis.isPresent() && analysis.get().getBpm() != null) {
                return analysis.get().getBpm();
            }
        }

        return 0.0; // unknown
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

        // 从曲库查询
        String trackId = getCurrentTrackId();
        if (trackId != null && libraryService != null) {
            return libraryService.getTrack(trackId).orElse(null);
        }

        return null;
    }

    private String getCurrentTrackId() {
        PlaybackStatus status = playbackEngine.getStatus();
        if (status == null) {
            return null;
        }
        return status.getCurrentTrackId();
    }

    @Override
    public int getSourcePlayerNumber() {
        return 1; // 本地播放器固定为 1
    }

    // ====== 播放控制方法 ======

    public void load(TrackInfo track) {
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
        PlaybackStatus status = playbackEngine.getStatus();
        System.out.println("[LocalSourceInput] getPlaybackStatus: state=" + (status != null ? status.getState() : "null") + ", position=" + (status != null ? status.getPositionMs() : 0));
        return status;
    }

    public void close() {
        playbackEngine.close();
    }

    public PlaybackEngine getPlaybackEngine() {
        return playbackEngine;
    }

    public LocalLibraryService getLibraryService() {
        return libraryService;
    }
}