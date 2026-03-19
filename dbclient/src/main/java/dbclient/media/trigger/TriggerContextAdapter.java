package dbclient.media.trigger;

/**
 * 触发上下文适配器接口
 * 
 * 负责将不同来源的数据映射为统一的 TriggerContext
 * 
 * 实现类：
 * - CdjTriggerContextAdapter: CDJ / beat-link 数据适配
 * - LocalTriggerContextAdapter: 本地播放器数据适配
 */
public interface TriggerContextAdapter {

    /**
     * 构建当前时刻的触发上下文
     * 
     * @return TriggerContext
     */
    TriggerContext buildContext();

    /**
     * 获取适配器对应的来源
     * 
     * @return TriggerSource
     */
    TriggerSource getSource();

    /**
     * 检查适配器是否可用
     * 
     * @return 是否可用
     */
    boolean isAvailable();
}
