package dbclient.media.trigger;

/**
 * 触发条件
 * 
 * 定义何时触发动作
 * 
 * MVP 支持的条件类型：
 * - BEAT: 每 N 拍触发
 * - MARKER: 到达指定 Marker 触发
 * - POSITION: 到达指定位置（毫秒）触发
 * - STATE_CHANGE: 播放状态变化触发
 */
public class TriggerCondition {

    public enum ConditionType {
        BEAT,        // 每 N 拍触发
        MARKER,      // 到达 Marker 触发
        POSITION,    // 到达位置触发
        STATE_CHANGE // 状态变化触发
    }

    private ConditionType type;
    private TriggerSource source;        // CDJ / LOCAL / ANY
    private Integer beatInterval;        // 每 N 拍（BEAT 类型）
    private String markerId;             // Marker ID（MARKER 类型）
    private Long positionMs;             // 位置（POSITION 类型）
    private dbclient.media.model.PlaybackStatus.State targetState;  // 目标状态（STATE_CHANGE 类型）

    public TriggerCondition() {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TriggerCondition cond = new TriggerCondition();

        public Builder type(ConditionType type) { cond.type = type; return this; }
        public Builder source(TriggerSource source) { cond.source = source; return this; }
        public Builder beatInterval(Integer beatInterval) { cond.beatInterval = beatInterval; return this; }
        public Builder markerId(String markerId) { cond.markerId = markerId; return this; }
        public Builder positionMs(Long positionMs) { cond.positionMs = positionMs; return this; }
        public Builder targetState(dbclient.media.model.PlaybackStatus.State targetState) { cond.targetState = targetState; return this; }

        public TriggerCondition build() {
            return cond;
        }
    }

    // Getters and Setters
    public ConditionType getType() { return type; }
    public void setType(ConditionType type) { this.type = type; }

    public TriggerSource getSource() { return source; }
    public void setSource(TriggerSource source) { this.source = source; }

    public Integer getBeatInterval() { return beatInterval; }
    public void setBeatInterval(Integer beatInterval) { this.beatInterval = beatInterval; }

    public String getMarkerId() { return markerId; }
    public void setMarkerId(String markerId) { this.markerId = markerId; }

    public Long getPositionMs() { return positionMs; }
    public void setPositionMs(Long positionMs) { this.positionMs = positionMs; }

    public dbclient.media.model.PlaybackStatus.State getTargetState() { return targetState; }
    public void setTargetState(dbclient.media.model.PlaybackStatus.State targetState) { this.targetState = targetState; }

    @Override
    public String toString() {
        return "TriggerCondition{" +
                "type=" + type +
                ", source=" + source +
                '}';
    }
}
