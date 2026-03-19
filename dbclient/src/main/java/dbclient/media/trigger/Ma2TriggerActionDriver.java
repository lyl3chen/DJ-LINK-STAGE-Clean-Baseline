package dbclient.media.trigger;

/**
 * MA2 触发动作驱动
 * 
 * MVP 支持的最小动作：
 * - SEND_COMMAND: 发送命令到 MA2
 * 
 * 与 Sync Outputs 的区别：
 * - Ma2BpmSyncDriver: 连续 BPM 推送
 * - Ma2TriggerActionDriver: 事件触发一次性命令
 * 
 * 当前状态：骨架实现
 * TODO: 接入现有 Ma2TelnetClient
 */
public class Ma2TriggerActionDriver implements TriggerActionDriver {

    // TODO: 注入 Ma2TelnetClient
    // private final Ma2TelnetClient ma2Client;

    public Ma2TriggerActionDriver() {
        // TODO: 注入 Ma2TelnetClient
    }

    @Override
    public String getName() {
        return "Ma2TriggerActionDriver";
    }

    @Override
    public String getProtocol() {
        return "MA2";
    }

    @Override
    public boolean execute(TriggerAction action, TriggerEvent event) {
        if (action == null || !isAvailable()) {
            return false;
        }

        String payload = action.getPayload();
        if (payload == null || payload.isEmpty()) {
            System.out.println("[Ma2TriggerActionDriver] No payload to send");
            return false;
        }

        // TODO: 接入 Ma2TelnetClient 发送命令
        // 示例：
        // try {
        //     ma2Client.sendCommand(payload);
        //     return true;
        // } catch (Exception e) {
        //     System.err.println("[Ma2TriggerActionDriver] Send failed: " + e.getMessage());
        //     return false;
        // }

        // MVP: 只打印日志
        System.out.println("[Ma2TriggerActionDriver] Would send: " + payload);
        return true;
    }

    @Override
    public boolean isAvailable() {
        // TODO: 检查 Ma2TelnetClient 是否已连接
        // return ma2Client != null && ma2Client.isConnected();
        return false;  // MVP 阶段默认不可用
    }

    // ==================== MVP 支持的动作类型 ====================
    
    /*
    // 后续可扩展的动作模板：
    // - "Macro 1" -> 执行宏
    // - "Executor 1.1" -> 触发执行器
    // - "Group 1" -> 触发组
    // - "Store Cue 1" -> 存储 Cue
    */
}
