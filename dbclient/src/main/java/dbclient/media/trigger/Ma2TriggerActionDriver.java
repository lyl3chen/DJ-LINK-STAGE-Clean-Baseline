package dbclient.media.trigger;

import dbclient.sync.SyncOutputManager;

/**
 * MA2 触发动作驱动
 * 
 * 最小实现：
 * - FIRE_MA2_EXEC: 触发 MA2 Executor
 * 
 * 复用现有 MA2 BPM 同步的 Telnet 发送能力：
 * - 通过 SyncOutputManager.sendMa2TestCommand() 发送命令
 * 
 * 不新建连接，复用已有 Ma2BpmDriver 的 client
 */
public class Ma2TriggerActionDriver implements TriggerActionDriver {

    private final SyncOutputManager syncOutputManager;

    public Ma2TriggerActionDriver(SyncOutputManager syncOutputManager) {
        this.syncOutputManager = syncOutputManager;
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
        if (action == null) {
            System.err.println("[Ma2TriggerActionDriver] Action is null");
            return false;
        }

        // 只处理 FIRE_MA2_EXEC 类型
        if (action.getType() != TriggerAction.ActionType.FIRE_MA2_EXEC) {
            System.out.println("[Ma2TriggerActionDriver] Unsupported action type: " + action.getType());
            return false;
        }

        if (!isAvailable()) {
            System.err.println("[Ma2TriggerActionDriver] SyncOutputManager not available");
            return false;
        }

        String payload = action.getPayload();
        if (payload == null || payload.isEmpty()) {
            payload = "Executor 1";  // 默认：触发 Executor 1
        }

        // 打印完整日志
        System.out.println("[Ma2TriggerActionDriver] Executing action");
        System.out.println("[Ma2TriggerActionDriver] Rule: " + event.getRuleName());
        System.out.println("[Ma2TriggerActionDriver] Source: " + event.getSource());
        System.out.println("[Ma2TriggerActionDriver] Position: " + event.getContext().getPositionMs() + "ms");
        System.out.println("[Ma2TriggerActionDriver] BPM: " + event.getContext().getBpm());
        System.out.println("[Ma2TriggerActionDriver] Beat: " + event.getContext().getBeatNumber());
        System.out.println("[Ma2TriggerActionDriver] Command to send: " + payload);

        // 复用 SyncOutputManager 发送
        try {
            // 调用已有的 MA2 测试命令发送方法
            java.lang.reflect.Method method = syncOutputManager.getClass().getMethod("sendMa2TestCommand", String.class);
            Object result = method.invoke(syncOutputManager, payload);
            
            System.out.println("[Ma2TriggerActionDriver] Send result: " + result);
            return true;
        } catch (Exception e) {
            System.err.println("[Ma2TriggerActionDriver] Send failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return syncOutputManager != null;
    }
}
