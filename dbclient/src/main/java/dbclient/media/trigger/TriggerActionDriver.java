package dbclient.media.trigger;

/**
 * 触发动作驱动接口
 * 
 * 负责将 TriggerAction 翻译成具体的协议调用
 * 
 * 与 Sync Outputs 的区别：
 * - TriggerActionDriver: 事件驱动，一次性动作
 * - OutputDriver (Sync): 连续同步输出（如 BPM 推送）
 * 
 * 实现类：
 * - TitanTriggerActionDriver: Titan 协议动作驱动
 * - Ma2TriggerActionDriver: MA2 协议动作驱动
 * - LogTriggerActionDriver: 日志驱动（调试用）
 */
public interface TriggerActionDriver {

    /**
     * 获取驱动名称
     */
    String getName();

    /**
     * 获取支持的协议类型
     */
    String getProtocol();

    /**
     * 执行触发动作
     * 
     * @param action 触发动作
     * @param event 触发事件上下文
     * @return 是否执行成功
     */
    boolean execute(TriggerAction action, TriggerEvent event);

    /**
     * 检查驱动是否可用
     */
    boolean isAvailable();
}
