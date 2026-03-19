package dbclient.media.trigger;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.BeatGrid;
import dbclient.media.model.MarkerPoint;
import dbclient.media.model.PlaybackStatus;
import dbclient.media.model.WaveformPreview;

/**
 * 统一触发上下文
 * 
 * 这是 Trigger Engine 消费的唯一数据结构
 * 不区分来源（CDJ / Local），只暴露统一的语义字段
 * 
 * 字段来源划分：
 * - runtime: 来自播放实时状态（播放位置、BPM、phase 等）
 * - analysis: 来自分析资产（BPM、beatGrid、waveform）
 * - marker: 来自用户标记（markers 列表）
 * 
 * MVP 允许为空的字段：
 * - beatGrid / waveformPreview: 本地模式可能未分析
 * - markers: 可能尚未创建任何标记
 * - phase / beatNumber / measureNumber: 依赖有效的 beatGrid
 */
public class TriggerContext {

    // ==================== 来源标识 ====================
    private TriggerSource source;  // CDJ / LOCAL

    // ==================== 曲目信息 ====================
    private String trackId;
    private String title;
    private String artist;
    private long durationMs;

    // ==================== 播放状态（runtime） ====================
    private PlaybackStatus.State playbackState;  // STOPPED / PLAYING / PAUSED
    private long positionMs;              // 当前毫秒位置
    private double phase;                  // 0.0-1.0，当前拍内相位

    // ==================== 节拍信息（runtime + analysis） ====================
    private Integer bpm;                    // BPM（来自 analysis 或实时）
    private Integer beatNumber;            // 当前第几拍（从 0 开始）
    private Integer measureNumber;         // 当前第几小节（从 0 开始）
    private long nextBeatMs;              // 下一拍时间
    private long nextMeasureMs;           // 下一小节时间

    // ==================== 分析数据（analysis） ====================
    private AnalysisResult analysis;        // 完整分析结果
    private BeatGrid beatGrid;             // 节拍网格（允许 null）
    private WaveformPreview waveformPreview; // 波形预览（允许 null）

    // ==================== 标记数据（marker） ====================
    private java.util.List<MarkerPoint> markers;  // 标记列表（允许 null）

    // ==================== 元数据 ====================
    private long timestamp;                // 上下文生成时间

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TriggerContext ctx = new TriggerContext();

        public Builder source(TriggerSource source) { ctx.source = source; return this; }
        public Builder trackId(String trackId) { ctx.trackId = trackId; return this; }
        public Builder title(String title) { ctx.title = title; return this; }
        public Builder artist(String artist) { ctx.artist = artist; return this; }
        public Builder durationMs(long durationMs) { ctx.durationMs = durationMs; return this; }
        public Builder playbackState(PlaybackStatus.State playbackState) { ctx.playbackState = playbackState; return this; }
        public Builder positionMs(long positionMs) { ctx.positionMs = positionMs; return this; }
        public Builder phase(double phase) { ctx.phase = phase; return this; }
        public Builder bpm(Integer bpm) { ctx.bpm = bpm; return this; }
        public Builder beatNumber(Integer beatNumber) { ctx.beatNumber = beatNumber; return this; }
        public Builder measureNumber(Integer measureNumber) { ctx.measureNumber = measureNumber; return this; }
        public Builder nextBeatMs(long nextBeatMs) { ctx.nextBeatMs = nextBeatMs; return this; }
        public Builder nextMeasureMs(long nextMeasureMs) { ctx.nextMeasureMs = nextMeasureMs; return this; }
        public Builder analysis(AnalysisResult analysis) { ctx.analysis = analysis; return this; }
        public Builder beatGrid(BeatGrid beatGrid) { ctx.beatGrid = beatGrid; return this; }
        public Builder waveformPreview(WaveformPreview waveformPreview) { ctx.waveformPreview = waveformPreview; return this; }
        public Builder markers(java.util.List<MarkerPoint> markers) { ctx.markers = markers; return this; }
        public Builder timestamp(long timestamp) { ctx.timestamp = timestamp; return this; }

        public TriggerContext build() {
            if (ctx.timestamp == 0) {
                ctx.timestamp = System.currentTimeMillis();
            }
            return ctx;
        }
    }

    // Getters and Setters
    public TriggerSource getSource() { return source; }
    public void setSource(TriggerSource source) { this.source = source; }

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public PlaybackStatus.State getPlaybackState() { return playbackState; }
    public void setPlaybackState(PlaybackStatus.State playbackState) { this.playbackState = playbackState; }

    public long getPositionMs() { return positionMs; }
    public void setPositionMs(long positionMs) { this.positionMs = positionMs; }

    public double getPhase() { return phase; }
    public void setPhase(double phase) { this.phase = phase; }

    public Integer getBpm() { return bpm; }
    public void setBpm(Integer bpm) { this.bpm = bpm; }

    public Integer getBeatNumber() { return beatNumber; }
    public void setBeatNumber(Integer beatNumber) { this.beatNumber = beatNumber; }

    public Integer getMeasureNumber() { return measureNumber; }
    public void setMeasureNumber(Integer measureNumber) { this.measureNumber = measureNumber; }

    public long getNextBeatMs() { return nextBeatMs; }
    public void setNextBeatMs(long nextBeatMs) { this.nextBeatMs = nextBeatMs; }

    public long getNextMeasureMs() { return nextMeasureMs; }
    public void setNextMeasureMs(long nextMeasureMs) { this.nextMeasureMs = nextMeasureMs; }

    public AnalysisResult getAnalysis() { return analysis; }
    public void setAnalysis(AnalysisResult analysis) { this.analysis = analysis; }

    public BeatGrid getBeatGrid() { return beatGrid; }
    public void setBeatGrid(BeatGrid beatGrid) { this.beatGrid = beatGrid; }

    public WaveformPreview getWaveformPreview() { return waveformPreview; }
    public void setWaveformPreview(WaveformPreview waveformPreview) { this.waveformPreview = waveformPreview; }

    public java.util.List<MarkerPoint> getMarkers() { return markers; }
    public void setMarkers(java.util.List<MarkerPoint> markers) { this.markers = markers; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    // ==================== 便捷方法 ====================

    /**
     * 判断是否有有效的 Beat Grid
     */
    public boolean hasBeatGrid() {
        return beatGrid != null && beatGrid.isValid();
    }

    /**
     * 判断是否有波形预览
     */
    public boolean hasWaveform() {
        return waveformPreview != null && waveformPreview.isValid();
    }

    /**
     * 判断是否有 Markers
     */
    public boolean hasMarkers() {
        return markers != null && !markers.isEmpty();
    }

    /**
     * 判断是否正在播放
     */
    public boolean isPlaying() {
        return playbackState == PlaybackStatus.State.PLAYING;
    }

    /**
     * 获取启用的 Markers
     */
    public java.util.List<MarkerPoint> getEnabledMarkers() {
        if (markers == null) return new java.util.ArrayList<>();
        java.util.List<MarkerPoint> enabled = new java.util.ArrayList<>();
        for (MarkerPoint m : markers) {
            if (m.isEnabled()) enabled.add(m);
        }
        return enabled;
    }

    @Override
    public String toString() {
        return "TriggerContext{" +
                "source=" + source +
                ", trackId='" + trackId + '\'' +
                ", title='" + title + '\'' +
                ", playbackState=" + playbackState +
                ", positionMs=" + positionMs +
                ", bpm=" + bpm +
                ", phase=" + phase +
                '}';
    }
}
