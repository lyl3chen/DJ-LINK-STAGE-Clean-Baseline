package dbclient.media.trigger;

import dbclient.media.library.TrackLibraryService;
import dbclient.media.model.*;

/**
 * 本地播放器触发上下文适配器
 * 
 * 数据来源：
 * - 播放状态: BasicLocalPlaybackEngine
 * - 分析资产: TrackLibraryService / AnalysisService
 * 
 * MVP 字段映射：
 * - source: LOCAL
 * - trackId/title/artist/durationMs: 从 TrackInfo 读取
 * - playbackState/positionMs: 从 PlaybackStatus 读取
 * - bpm: 从 AnalysisResult 读取
 * - beatGrid: 从 AnalysisResult 读取（或 AnalysisService 生成）
 * - waveformPreview: 从 AnalysisResult 读取
 * - markers: 从 TrackLibraryService 读取
 * - phase/beatNumber/measureNumber: 基于 beatGrid 计算（如果有）
 */
public class LocalTriggerContextAdapter implements TriggerContextAdapter {

    private final dbclient.media.player.BasicLocalPlaybackEngine playbackEngine;
    private final TrackLibraryService trackLibraryService;
    private final dbclient.media.analysis.AnalysisService analysisService;

    public LocalTriggerContextAdapter(
            dbclient.media.player.BasicLocalPlaybackEngine playbackEngine,
            TrackLibraryService trackLibraryService,
            dbclient.media.analysis.AnalysisService analysisService) {
        this.playbackEngine = playbackEngine;
        this.trackLibraryService = trackLibraryService;
        this.analysisService = analysisService;
    }

    @Override
    public TriggerContext buildContext() {
        TriggerContext.Builder builder = TriggerContext.builder()
            .source(TriggerSource.LOCAL)
            .timestamp(System.currentTimeMillis());

        // 获取播放状态
        if (playbackEngine != null) {
            PlaybackStatus status = playbackEngine.getStatus();
            if (status != null) {
                builder.playbackState(status.getState());
                builder.positionMs(status.getPositionMs());

                // 获取当前曲目信息：从 playbackEngine 直接获取
                TrackInfo track = playbackEngine.getCurrentTrack();
                if (track != null) {
                    builder.trackId(track.getTrackId());
                    builder.title(track.getTitle());
                    builder.artist(track.getArtist());
                    builder.durationMs(track.getDurationMs());

                    // 获取分析结果
                    if (trackLibraryService != null) {
                        java.util.Optional<TrackLibraryEntry> entryOpt = trackLibraryService.findByTrackId(track.getTrackId());
                        if (entryOpt.isPresent()) {
                            TrackLibraryEntry entry = entryOpt.get();
                            AnalysisResult analysis = entry.getAnalysis();
                            
                            if (analysis != null) {
                                builder.analysis(analysis);
                                builder.bpm(analysis.getBpm());
                                builder.beatGrid(analysis.getBeatGrid());
                                builder.waveformPreview(analysis.getWaveformPreview());
                            }

                            // Markers - 统一为空列表而非 null
                            if (entry.hasMarkers()) {
                                builder.markers(entry.getMarkers());
                            } else {
                                builder.markers(new java.util.ArrayList<>());
                            }
                        }
                    }
                }
            }
        }

        // 计算相位和节拍信息（基于 beatGrid，无 beatGrid 时保持 null）
        TriggerContext ctx = builder.build();
        enrichBeatInfo(ctx);

        return ctx;
    }

    /**
     * 基于 beatGrid 丰富节拍信息
     */
    private void enrichBeatInfo(TriggerContext ctx) {
        if (!ctx.hasBeatGrid()) {
            return;
        }

        BeatGrid bg = ctx.getBeatGrid();
        long pos = ctx.getPositionMs();

        // 计算相位
        double phase = bg.getPhaseAtTime(pos);
        ctx.setPhase(phase);

        // 当前 Beat 和小节
        int beat = bg.timeToBeat(pos);
        int measure = bg.getMeasureAtTime(pos);
        ctx.setBeatNumber(beat);
        ctx.setMeasureNumber(measure);

        // 下一拍/小节时间
        ctx.setNextBeatMs(bg.getNextBeatTime(pos));
        ctx.setNextMeasureMs(bg.getNextMeasureTime(pos));
    }

    @Override
    public TriggerSource getSource() {
        return TriggerSource.LOCAL;
    }

    @Override
    public boolean isAvailable() {
        return playbackEngine != null;
    }
}
