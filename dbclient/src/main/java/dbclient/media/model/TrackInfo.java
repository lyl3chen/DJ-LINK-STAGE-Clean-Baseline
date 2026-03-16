package dbclient.media.model;

/**
 * 曲目静态信息 DTO
 * 只包含曲目本身的元数据，不包含分析状态或播放状态
 */
public class TrackInfo {
    private String trackId;
    private String filePath;
    private String title;
    private String artist;
    private String album;
    private long durationMs;
    private int sampleRate;
    private int channels;

    public TrackInfo() {}

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

        public Builder sampleRate(int sampleRate) {
            track.sampleRate = sampleRate;
            return this;
        }

        public Builder channels(int channels) {
            track.channels = channels;
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

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

    public int getChannels() { return channels; }
    public void setChannels(int channels) { this.channels = channels; }

    @Override
    public String toString() {
        return "TrackInfo{" +
                "trackId='" + trackId + '\'' +
                ", title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", durationMs=" + durationMs +
                '}';
    }
}
