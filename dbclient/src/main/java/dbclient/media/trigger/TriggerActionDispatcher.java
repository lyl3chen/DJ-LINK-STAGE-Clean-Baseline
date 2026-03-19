package dbclient.media.trigger;

import dbclient.sync.SyncOutputManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 触发动作分发器
 * 
 * 职责：
 * - 注册 TriggerActionDriver
 * - 根据动作的 protocol 找到对应的驱动并执行
 * - 不执行动作，只负责路由到正确的驱动
 * 
 * 与 TriggerEngine 的关系：
 * - Engine 负责"命中什么规则"
 * - Dispatcher 负责"把动作发送到正确的驱动"
 * 
 * 与 Sync Outputs 的关系：
 * - Dispatcher 只处理事件触发的动作
 * - Sync Outputs (TitanBpmSyncDriver 等) 处理连续同步输出
 * - 两者互不干扰，各自独立
 */
public class TriggerActionDispatcher {

    // protocol -> driver
    private final Map<String, TriggerActionDriver> drivers = new ConcurrentHashMap<>();
    
    // MA2 驱动专用：需要 SyncOutputManager
    private TriggerActionDriver ma2Driver = null;

    public TriggerActionDispatcher() {
        // 注册默认驱动
        registerDriver(new LogTriggerActionDriver());
    }

    /**
     * 设置 MA2 驱动（需要 SyncOutputManager）
     */
    public void setMa2Driver(TriggerActionDriver driver) {
        this.ma2Driver = driver;
        if (driver != null) {
            registerDriver(driver);
            System.out.println("[TriggerActionDispatcher] MA2 driver configured: " + driver.getName());
        }
    }

    /**
     * 注册动作驱动
     */
    public void registerDriver(TriggerActionDriver driver) {
        if (driver != null && driver.getProtocol() != null) {
            drivers.put(driver.getProtocol().toUpperCase(), driver);
            System.out.println("[TriggerActionDispatcher] Registered driver: " + driver.getName() + " for protocol: " + driver.getProtocol());
        }
    }

    /**
     * 移除动作驱动
     */
    public void unregisterDriver(String protocol) {
        if (protocol != null) {
            drivers.remove(protocol.toUpperCase());
        }
    }

    /**
     * 分发动作到对应驱动
     * 
     * @param action 触发动作
     * @param event 触发事件
     * @return 是否成功
     */
    public boolean dispatch(TriggerAction action, TriggerEvent event) {
        if (action == null) {
            System.err.println("[TriggerActionDispatcher] Action is null, skipping");
            return false;
        }

        // 打印分发日志
        System.out.println("[TriggerActionDispatcher] Dispatching action");
        System.out.println("[TriggerActionDispatcher] Action type: " + action.getType());
        System.out.println("[TriggerActionDispatcher] Action protocol: " + action.getProtocol());
        System.out.println("[TriggerActionDispatcher] Rule: " + event.getRuleName());

        // 处理内置类型
        switch (action.getType()) {
            case NONE:
                // 空动作，什么都不做
                return true;
            case LOG:
                // 日志动作，默认驱动处理
                break;
            case FIRE_MA2_EXEC:
                // MA2 触发：使用专用驱动
                System.out.println("[TriggerActionDispatcher] Using MA2 driver for FIRE_MA2_EXEC");
                if (ma2Driver != null && ma2Driver.isAvailable()) {
                    return ma2Driver.execute(action, event);
                } else {
                    System.err.println("[TriggerActionDispatcher] MA2 driver not available, falling back to LOG");
                    break;
                }
            case CALLBACK:
                // TODO: 回调处理
                return false;
            default:
                // 继续走协议路由
                break;
        }

        // 协议路由
        String protocol = action.getProtocol();
        if (protocol == null || protocol.isEmpty()) {
            // 没有协议，使用默认日志驱动
            protocol = "LOG";
        }

        TriggerActionDriver driver = drivers.get(protocol.toUpperCase());
        if (driver == null) {
            System.err.println("[TriggerActionDispatcher] No driver for protocol: " + protocol + 
                " | Rule: " + (event != null ? event.getRuleId() : "unknown") + 
                " | Falling back to LOG");
            // 回退到日志驱动
            driver = drivers.get("LOG");
            if (driver == null) {
                return false;
            }
        }

        if (!driver.isAvailable()) {
            System.err.println("[TriggerActionDispatcher] Driver unavailable: " + driver.getName() + 
                " | Protocol: " + protocol + 
                " | Rule: " + (event != null ? event.getRuleId() : "unknown") +
                " | Falling back to LOG");
            driver = drivers.get("LOG");
            if (driver == null) {
                return false;
            }
        }

        try {
            return driver.execute(action, event);
        } catch (Exception e) {
            System.err.println("[TriggerActionDispatcher] Driver execution error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取所有已注册的驱动
     */
    public List<TriggerActionDriver> getDrivers() {
        return new ArrayList<>(drivers.values());
    }

    /**
     * 获取驱动统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalDrivers", drivers.size());
        stats.put("protocols", new ArrayList<>(drivers.keySet()));
        return stats;
    }
}
