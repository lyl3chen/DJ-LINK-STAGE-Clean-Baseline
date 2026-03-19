package dbclient.media.trigger;

/**
 * 触发事件
 * 
 * 当 TriggerRule 匹配成功时生成的事件
 * 由 TriggerEngine 派发给 TriggerActionDispatcher
 */
public class TriggerEvent {

    private String eventId;
    private String ruleId;
    private String ruleName;
    private TriggerSource source;
    private TriggerAction action;
    private TriggerContext context;
    private long timestamp;
    private String message;  // 事件描述

    public TriggerEvent() {
        this.timestamp = System.currentTimeMillis();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TriggerEvent event = new TriggerEvent();

        public Builder eventId(String eventId) { event.eventId = eventId; return this; }
        public Builder ruleId(String ruleId) { event.ruleId = ruleId; return this; }
        public Builder ruleName(String ruleName) { event.ruleName = ruleName; return this; }
        public Builder source(TriggerSource source) { event.source = source; return this; }
        public Builder action(TriggerAction action) { event.action = action; return this; }
        public Builder context(TriggerContext context) { event.context = context; return this; }
        public Builder message(String message) { event.message = message; return this; }

        public TriggerEvent build() {
            return event;
        }
    }

    // Getters and Setters
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public TriggerSource getSource() { return source; }
    public void setSource(TriggerSource source) { this.source = source; }

    public TriggerAction getAction() { return action; }
    public void setAction(TriggerAction action) { this.action = action; }

    public TriggerContext getContext() { return context; }
    public void setContext(TriggerContext context) { this.context = context; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public String toString() {
        return "TriggerEvent{" +
                "ruleId='" + ruleId + '\'' +
                ", source=" + source +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
