package dbclient.media.model;

/**
 * 播放状态 DTO
 * 不依赖任何开源库类型
 */
public class PlaybackStatus {
    public enum State {
        STOPPED, PLAYING, PAUSED
    }

    private State state;
    private long positionMs;
    private double effectiveBpm;
    private String trackId;
    private double pitch;

    public PlaybackStatus() {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PlaybackStatus status = new PlaybackStatus();

        public Builder state(State state) {
            status.state = state;
            return this;
        }

        public Builder positionMs(long positionMs) {
            status.positionMs = positionMs;
            return this;
        }

        public Builder effectiveBpm(double effectiveBpm) {
            status.effectiveBpm = effectiveBpm;
            return this;
        }

        public Builder trackId(String trackId) {
            status.trackId = trackId;
            return this;
        }

        public Builder pitch(double pitch) {
            status.pitch = pitch;
            return this;
        }

        public PlaybackStatus build() {
            return status;
        }
    }

    // Getters and Setters
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public long getPositionMs() { return positionMs; }
    public void setPositionMs(long positionMs) { this.positionMs = positionMs; }

    public double getEffectiveBpm() { return effectiveBpm; }
    public void setEffectiveBpm(double effectiveBpm) { this.effectiveBpm = effectiveBpm; }

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public double getPitch() { return pitch; }
    public void setPitch(double pitch) { this.pitch = pitch; }

    @Override
    public String toString() {
        return "PlaybackStatus{" +
                "state=" + state +
                ", positionMs=" + positionMs +
                ", effectiveBpm=" + effectiveBpm +
                ", trackId='" + trackId + '\'' +
                '}';
    }
}
