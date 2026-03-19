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
 * 
 * 测试命令格式（来自 docs/MA2-OpenClaw-适配编程手册.md）：
 * - "Goto 1" - 触发 Executor 1
 */
public class Ma2TriggerActionDriver implements TriggerActionDriver {

    // 默认测试命令：Go Executor 1 Cue 1
    // 格式：Go Executor X Cue Y
    public static final String DEFAULT_MA2_TRIGGER_COMMAND = "Go Executor 1 Cue 1";
    
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
            payload = DEFAULT_MA2_TRIGGER_COMMAND;  // 默认命令
        }

        // 打印完整日志
        System.out.println("[Ma2TriggerActionDriver] ============================");
        System.out.println("[Ma2TriggerActionDriver] EXECUTING MA2 TRIGGER");
        System.out.println("[Ma2TriggerActionDriver] ============================");
        System.out.println("[Ma2TriggerActionDriver] Rule ID: " + event.getRuleId());
        System.out.println("[Ma2TriggerActionDriver] Rule Name: " + event.getRuleName());
        System.out.println("[Ma2TriggerActionDriver] Source: " + event.getSource());
        System.out.println("[Ma2TriggerActionDriver] Position: " + event.getContext().getPositionMs() + "ms");
        System.out.println("[Ma2TriggerActionDriver] BPM: " + event.getContext().getBpm());
        System.out.println("[Ma2TriggerActionDriver] Beat: " + event.getContext().getBeatNumber());
        System.out.println("[Ma2TriggerActionDriver] Timestamp: " + event.getTimestamp());
        System.out.println("[Ma2TriggerActionDriver] ---");
        System.out.println("[Ma2TriggerActionDriver] Command to send: [" + payload + "]");

        // 复用 SyncOutputManager 发送
        try {
            // 调用已有的 MA2 测试命令发送方法
            java.lang.reflect.Method method = syncOutputManager.getClass().getMethod("sendMa2TestCommand", String.class);
            Object resultObj = method.invoke(syncOutputManager, payload);
            
            if (resultObj instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> result = (java.util.Map<String, Object>) resultObj;
                
                System.out.println("[Ma2TriggerActionDriver] ---");
                System.out.println("[Ma2TriggerActionDriver] Send Result:");
                System.out.println("[Ma2TriggerActionDriver]   connected: " + result.get("connected"));
                System.out.println("[Ma2TriggerActionDriver]   sentCommand: " + result.get("sentCommand"));
                System.out.println("[Ma2TriggerActionDriver]   rawResponse: " + result.get("rawResponse"));
                System.out.println("[Ma2TriggerActionDriver]   error: " + result.get("error"));
                System.out.println("[Ma2TriggerActionDriver]   ok: " + result.get("ok"));
                System.out.println("[Ma2TriggerActionDriver] ============================");
                
                Boolean ok = (Boolean) result.get("ok");
                return ok != null && ok;
            }
            
            System.out.println("[Ma2TriggerActionDriver] Unexpected result type: " + resultObj.getClass());
            return false;
        } catch (Exception e) {
            System.err.println("[Ma2TriggerActionDriver] Send FAILED!");
            System.err.println("[Ma2TriggerActionDriver] Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            System.out.println("[Ma2TriggerActionDriver] ============================");
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return syncOutputManager != null;
    }
}
