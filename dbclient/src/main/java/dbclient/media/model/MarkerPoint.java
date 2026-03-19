package dbclient.media.model;

import java.util.UUID;

/**
 * Marker 点数据模型
 * 
 * 设计考虑：
 * - 一首曲目可保存多个 marker
 * - 支持多种类型（MARKER / CUE / TRIGGER / SECTION 等）
 * - 包含时间位置（timeMs）用于播放定位和触发计算
 * - 持久化存储，跨会话有效
 */
public class MarkerPoint {
    private String id;
    private String trackId;
    private String name;
    private long timeMs;
    private MarkerType type;
    private String note;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;

    public MarkerPoint() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.enabled = true;
        this.type = MarkerType.MARKER;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private MarkerPoint mp = new MarkerPoint();

        public Builder id(String id) { mp.id = id; return this; }
        public Builder trackId(String trackId) { mp.trackId = trackId; return this; }
        public Builder name(String name) { mp.name = name; return this; }
        public Builder timeMs(long timeMs) { mp.timeMs = timeMs; return this; }
        public Builder type(MarkerType type) { mp.type = type; return this; }
        public Builder note(String note) { mp.note = note; return this; }
        public Builder enabled(boolean enabled) { mp.enabled = enabled; return this; }
        public Builder createdAt(long createdAt) { mp.createdAt = createdAt; return this; }
        public Builder updatedAt(long updatedAt) { mp.updatedAt = updatedAt; return this; }

        public MarkerPoint build() {
            return mp;
        }
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getTimeMs() { return timeMs; }
    public void setTimeMs(long timeMs) { this.timeMs = timeMs; }

    public MarkerType getType() { return type; }
    public void setType(MarkerType type) { this.type = type; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 更新时自动刷新 updatedAt
     */
    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "MarkerPoint{" +
                "id='" + id + '\'' +
                ", trackId='" + trackId + '\'' +
                ", name='" + name + '\'' +
                ", timeMs=" + timeMs +
                ", type=" + type +
                ", enabled=" + enabled +
                '}';
    }
}
