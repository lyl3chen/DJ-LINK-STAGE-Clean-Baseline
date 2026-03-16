package dbclient.media.model;

/**
 * 播放状态 DTO
 * 只表达当前播放状态，不包含曲目静态信息
 */
public class PlaybackStatus {
    public enum State {
        STOPPED, PLAYING, PAUSED
    }

    private State state;
    private long positionMs;
    private long durationMs;
    private double effectiveBpm;
    private double pitch;
    private String currentTrackId;

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

        public Builder durationMs(long durationMs) {
            status.durationMs = durationMs;
            return this;
        }

        public Builder effectiveBpm(double effectiveBpm) {
            status.effectiveBpm = effectiveBpm;
            return this;
        }

        public Builder pitch(double pitch) {
            status.pitch = pitch;
            return this;
        }

        public Builder currentTrackId(String currentTrackId) {
            status.currentTrackId = currentTrackId;
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

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public double getEffectiveBpm() { return effectiveBpm; }
    public void setEffectiveBpm(double effectiveBpm) { this.effectiveBpm = effectiveBpm; }

    public double getPitch() { return pitch; }
    public void setPitch(double pitch) { this.pitch = pitch; }

    public String getCurrentTrackId() { return currentTrackId; }
    public void setCurrentTrackId(String currentTrackId) { this.currentTrackId = currentTrackId; }

    // Convenience methods
    public boolean isPlaying() { return state == State.PLAYING; }
    public boolean isPaused() { return state == State.PAUSED; }
    public boolean isStopped() { return state == State.STOPPED; }

    @Override
    public String toString() {
        return "PlaybackStatus{" +
                "state=" + state +
                ", positionMs=" + positionMs +
                ", durationMs=" + durationMs +
                ", effectiveBpm=" + effectiveBpm +
                '}';
    }
}
