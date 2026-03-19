package dbclient.media.trigger;

/**
 * 日志动作驱动（调试用）
 */
public class LogTriggerActionDriver implements TriggerActionDriver {

    @Override
    public String getName() {
        return "LogTriggerActionDriver";
    }

    @Override
    public String getProtocol() {
        return "LOG";
    }

    @Override
    public boolean execute(TriggerAction action, TriggerEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TRIGGER] ");
        sb.append(event.getMessage());
        
        if (action.getPayload() != null && !action.getPayload().isEmpty()) {
            sb.append(" | Payload: ").append(action.getPayload());
        }
        
        System.out.println(sb.toString());
        return true;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
