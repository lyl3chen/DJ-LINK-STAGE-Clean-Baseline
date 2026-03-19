package dbclient.media.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 曲目库条目（统一曲目资产）
 * 
 * 设计考虑：
 * - 统一关联：本地文件 + 分析结果 + Marker 资产
 * - 作为本地曲库的统一入口
 * - 跨会话持久化
 * - 与 TrackInfo 的区别：TrackInfo 是播放时使用的轻量对象，TrackLibraryEntry 是资产层
 */
public class TrackLibraryEntry {
    private String entryId;          // 资产层唯一 ID（UUID）
    private String trackId;          // 关联的 TrackInfo ID（可能与 entryId 相同）
    private String filePath;         // 文件路径
    private String title;            // 标题
    private String artist;           // 艺术家
    private long durationMs;         // 时长（毫秒）
    private int sampleRate;          // 采样率
    private int channels;            // 声道数
    private long fileSize;           // 文件大小（字节）
    private String fileHash;         // 文件哈希（用于去重）
    
    // 关联资产
    private AnalysisResult analysis; // 分析结果
    private List<MarkerPoint> markers; // Marker 列表
    
    // 元数据
    private long importedAt;         // 导入时间
    private long updatedAt;         // 最后更新时间

    public TrackLibraryEntry() {
        this.entryId = UUID.randomUUID().toString();
        this.importedAt = System.currentTimeMillis();
        this.updatedAt = this.importedAt;
        this.markers = new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TrackLibraryEntry e = new TrackLibraryEntry();

        public Builder entryId(String entryId) { e.entryId = entryId; return this; }
        public Builder trackId(String trackId) { e.trackId = trackId; return this; }
        public Builder filePath(String filePath) { e.filePath = filePath; return this; }
        public Builder title(String title) { e.title = title; return this; }
        public Builder artist(String artist) { e.artist = artist; return this; }
        public Builder durationMs(long durationMs) { e.durationMs = durationMs; return this; }
        public Builder sampleRate(int sampleRate) { e.sampleRate = sampleRate; return this; }
        public Builder channels(int channels) { e.channels = channels; return this; }
        public Builder fileSize(long fileSize) { e.fileSize = fileSize; return this; }
        public Builder fileHash(String fileHash) { e.fileHash = fileHash; return this; }
        public Builder analysis(AnalysisResult analysis) { e.analysis = analysis; return this; }
        public Builder markers(List<MarkerPoint> markers) { e.markers = markers; return this; }
        public Builder importedAt(long importedAt) { e.importedAt = importedAt; return this; }
        public Builder updatedAt(long updatedAt) { e.updatedAt = updatedAt; return this; }

        public TrackLibraryEntry build() {
            return e;
        }
    }

    // Getters and Setters
    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

    public int getChannels() { return channels; }
    public void setChannels(int channels) { this.channels = channels; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public AnalysisResult getAnalysis() { return analysis; }
    public void setAnalysis(AnalysisResult analysis) { 
        this.analysis = analysis; 
        touch();
    }

    public List<MarkerPoint> getMarkers() { return markers; }
    public void setMarkers(List<MarkerPoint> markers) { 
        this.markers = markers; 
        touch();
    }

    public long getImportedAt() { return importedAt; }
    public void setImportedAt(long importedAt) { this.importedAt = importedAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    // ==================== 便捷方法 ====================

    /**
     * 判断是否有分析结果
     */
    public boolean hasAnalysis() {
        return analysis != null && analysis.getAnalysisStatus() != null 
            && analysis.getAnalysisStatus() == AnalysisStatus.COMPLETED;
    }

    /**
     * 判断是否有 BPM
     */
    public boolean hasBpm() {
        return hasAnalysis() && analysis.getBpm() != null;
    }

    /**
     * 判断是否有 Beat Grid
     */
    public boolean hasBeatGrid() {
        return hasAnalysis() && analysis.getBeatGrid() != null && analysis.getBeatGrid().isValid();
    }

    /**
     * 判断是否有 Markers
     */
    public boolean hasMarkers() {
        return markers != null && !markers.isEmpty();
    }

    /**
     * 获取启用的 Markers
     */
    public List<MarkerPoint> getEnabledMarkers() {
        if (markers == null) return new ArrayList<>();
        List<MarkerPoint> enabled = new ArrayList<>();
        for (MarkerPoint m : markers) {
            if (m.isEnabled()) {
                enabled.add(m);
            }
            // 如果 m 是 null，跳过
        }
        return enabled;
    }

    /**
     * 添加 Marker
     */
    public void addMarker(MarkerPoint marker) {
        if (markers == null) {
            markers = new ArrayList<>();
        }
        markers.add(marker);
        touch();
    }

    /**
     * 移除 Marker
     */
    public void removeMarker(String markerId) {
        if (markers != null) {
            markers.removeIf(m -> m.getId().equals(markerId));
            touch();
        }
    }

    /**
     * 更新时间戳
     */
    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "TrackLibraryEntry{" +
                "entryId='" + entryId + '\'' +
                ", trackId='" + trackId + '\'' +
                ", title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", durationMs=" + durationMs +
                ", hasAnalysis=" + hasAnalysis() +
                ", markerCount=" + (markers != null ? markers.size() : 0) +
                '}';
    }
}
