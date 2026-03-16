package dbclient.media.model;

/**
 * 曲目信息 DTO
 * 不依赖任何开源库类型
 */
public class TrackInfo {
    private String trackId;
    private String filePath;
    private String title;
    private String artist;
    private String album;
    private long durationMs;
    private Integer bpm;
    private String key;
    private AnalysisStatus analysisStatus;
    private String waveformCachePath;
    private String beatGridPath;
    private long addedAt;
    private long lastPlayedAt;

    public TrackInfo() {}

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TrackInfo track = new TrackInfo();

        public Builder trackId(String trackId) {
            track.trackId = trackId;
            return this;
        }

        public Builder filePath(String filePath) {
            track.filePath = filePath;
            return this;
        }

        public Builder title(String title) {
            track.title = title;
            return this;
        }

        public Builder artist(String artist) {
            track.artist = artist;
            return this;
        }

        public Builder album(String album) {
            track.album = album;
            return this;
        }

        public Builder durationMs(long durationMs) {
            track.durationMs = durationMs;
            return this;
        }

        public Builder bpm(Integer bpm) {
            track.bpm = bpm;
            return this;
        }

        public Builder key(String key) {
            track.key = key;
            return this;
        }

        public Builder analysisStatus(AnalysisStatus analysisStatus) {
            track.analysisStatus = analysisStatus;
            return this;
        }

        public Builder waveformCachePath(String waveformCachePath) {
            track.waveformCachePath = waveformCachePath;
            return this;
        }

        public Builder beatGridPath(String beatGridPath) {
            track.beatGridPath = beatGridPath;
            return this;
        }

        public Builder addedAt(long addedAt) {
            track.addedAt = addedAt;
            return this;
        }

        public Builder lastPlayedAt(long lastPlayedAt) {
            track.lastPlayedAt = lastPlayedAt;
            return this;
        }

        public TrackInfo build() {
            return track;
        }
    }

    // Getters and Setters
    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public Integer getBpm() { return bpm; }
    public void setBpm(Integer bpm) { this.bpm = bpm; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public AnalysisStatus getAnalysisStatus() { return analysisStatus; }
    public void setAnalysisStatus(AnalysisStatus analysisStatus) { this.analysisStatus = analysisStatus; }

    public String getWaveformCachePath() { return waveformCachePath; }
    public void setWaveformCachePath(String waveformCachePath) { this.waveformCachePath = waveformCachePath; }

    public String getBeatGridPath() { return beatGridPath; }
    public void setBeatGridPath(String beatGridPath) { this.beatGridPath = beatGridPath; }

    public long getAddedAt() { return addedAt; }
    public void setAddedAt(long addedAt) { this.addedAt = addedAt; }

    public long getLastPlayedAt() { return lastPlayedAt; }
    public void setLastPlayedAt(long lastPlayedAt) { this.lastPlayedAt = lastPlayedAt; }

    @Override
    public String toString() {
        return "TrackInfo{" +
                "trackId='" + trackId + '\'' +
                ", title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", durationMs=" + durationMs +
                ", bpm=" + bpm +
                ", analysisStatus=" + analysisStatus +
                '}';
    }
}
