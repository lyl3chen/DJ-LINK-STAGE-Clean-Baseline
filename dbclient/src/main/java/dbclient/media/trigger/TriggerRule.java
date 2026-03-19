package dbclient.media.trigger;

/**
 * 触发规则
 * 
 * 包含触发条件 + 触发动作
 * 由 TriggerEngine 管理并在运行时评估
 */
public class TriggerRule {

    private String id;
    private String name;
    private TriggerCondition condition;
    private TriggerAction action;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;

    public TriggerRule() {
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TriggerRule rule = new TriggerRule();

        public Builder id(String id) { rule.id = id; return this; }
        public Builder name(String name) { rule.name = name; return this; }
        public Builder condition(TriggerCondition condition) { rule.condition = condition; return this; }
        public Builder action(TriggerAction action) { rule.action = action; return this; }
        public Builder enabled(boolean enabled) { rule.enabled = enabled; return this; }

        public TriggerRule build() {
            return rule;
        }
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TriggerCondition getCondition() { return condition; }
    public void setCondition(TriggerCondition condition) { this.condition = condition; }

    public TriggerAction getAction() { return action; }
    public void setAction(TriggerAction action) { this.action = action; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "TriggerRule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
