package dbclient.media.trigger;

/**
 * Titan 触发动作驱动
 * 
 * MVP 支持的最小动作：
 * - SEND_COMMAND: 发送命令到 Titan
 * 
 * 与 Sync Outputs 的区别：
 * - TitanBpmSyncDriver: 连续 BPM 推送（每秒多次）
 * - TitanTriggerActionDriver: 事件触发一次性命令
 * 
 * 当前状态：骨架实现
 * TODO: 接入现有 TitanClient
 */
public class TitanTriggerActionDriver implements TriggerActionDriver {

    // TODO: 注入 TitanClient
    // private final TitanClient titanClient;

    public TitanTriggerActionDriver() {
        // TODO: 注入 TitanClient
    }

    @Override
    public String getName() {
        return "TitanTriggerActionDriver";
    }

    @Override
    public String getProtocol() {
        return "TITAN";
    }

    @Override
    public boolean execute(TriggerAction action, TriggerEvent event) {
        if (action == null || !isAvailable()) {
            return false;
        }

        String payload = action.getPayload();
        if (payload == null || payload.isEmpty()) {
            System.out.println("[TitanTriggerActionDriver] No payload to send");
            return false;
        }

        // TODO: 接入 TitanClient 发送命令
        // 示例：
        // try {
        //     titanClient.sendCommand(payload);
        //     return true;
        // } catch (Exception e) {
        //     System.err.println("[TitanTriggerActionDriver] Send failed: " + e.getMessage());
        //     return false;
        // }

        // MVP: 只打印日志
        System.out.println("[TitanTriggerActionDriver] Would send: " + payload);
        return true;
    }

    @Override
    public boolean isAvailable() {
        // TODO: 检查 TitanClient 是否已连接
        // return titanClient != null && titanClient.isConnected();
        return false;  // MVP 阶段默认不可用
    }

    // ==================== MVP 支持的动作类型 ====================
    
    /*
    // 后续可扩展的动作模板：
    // - "BUTTON_PRESS:Page1.Button1" -> 按下按钮
    // - "FADER:Master=100" -> 设置推子
    // - "CUE:Page1.Cue1" -> 触发 Cue
    // - "OSC:/desk/1/btn/1 1" -> 发送 OSC
    */
}
