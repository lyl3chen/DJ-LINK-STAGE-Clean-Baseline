package dbclient.media.trigger;

/**
 * 触发动作
 * 
 * 定义触发时执行什么动作
 * 
 * MVP 支持的动作类型：
 * - NONE: 空动作（调试用）
 * - LOG: 记录日志
 * - CALLBACK: 调用回调
 * 
 * 后续可扩展：
 * - SEND_COMMAND: 发送协议命令（Titan/MA2）
 * - INVOKE_API: 调用 HTTP API
 * - INVOKE_OSC: 发送 OSC 消息
 */
public class TriggerAction {

    public enum ActionType {
        NONE,           // 空动作
        LOG,            // 记录日志
        CALLBACK,       // 调用回调
        FIRE_MA2_EXEC  // 触发 MA2 Executor（最小实现：复用现有 Telnet 发送）
    }

    private ActionType type;
    private String protocol;      // TITAN / MA2 / OSC / MIDI / HTTP
    private String payload;       // 命令/消息模板
    private String description;    // 描述

    public TriggerAction() {
        this.type = ActionType.NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TriggerAction action = new TriggerAction();

        public Builder type(ActionType type) { action.type = type; return this; }
        public Builder protocol(String protocol) { action.protocol = protocol; return this; }
        public Builder payload(String payload) { action.payload = payload; return this; }
        public Builder description(String description) { action.description = description; return this; }

        public TriggerAction build() {
            return action;
        }
    }

    // Getters and Setters
    public ActionType getType() { return type; }
    public void setType(ActionType type) { this.type = type; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public String toString() {
        return "TriggerAction{" +
                "type=" + type +
                ", protocol='" + protocol + '\'' +
                ", payload='" + payload + '\'' +
                '}';
    }
}
