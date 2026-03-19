package dbclient.protocol;

/**
 * MA2 协议客户端接口
 * 
 * 职责：
 * - 建立与 MA2 服务器的 Telnet 连接
 * - 保持连接（心跳/保活）
 * - 重连机制
 * - 命令发送
 * - 响应处理
 * - 错误处理
 * 
 * 复用方：
 * - Ma2BpmSyncDriver: 连续 BPM 推送
 * - Ma2TriggerActionDriver: 事件触发动作
 */
public interface Ma2Client extends ProtocolClient<Ma2Client.ConnectionState> {

    /**
     * 连接状态
     */
    enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        LOGIN,
        LOGGED_IN,
        ERROR
    }

    /**
     * 发送 MA2 命令并获取响应
     * 
     * @param command MA2 命令
     * @return 响应
     */
    String sendMa2Command(String command);

    /**
     * 发送 MA2 命令（简化版）
     * 
     * @param command 命令
     * @return 是否成功
     */
    boolean sendMa2CommandSimple(String command);

    /**
     * 执行宏
     * 
     * @param macro 宏编号
     */
    void execMacro(int macro);

    /**
     * 触发执行器
     * 
     * @param executor 执行器编号
     */
    void triggerExecutor(int executor);

    /**
     * 触发执行器（带页码）
     * 
     * @param page 页码
     * @param executor 执行器编号
     */
    void triggerExecutor(int page, int executor);

    /**
     * 存储 Cue
     * 
     * @param cue Cue 编号
     */
    void storeCue(int cue);

    /**
     * 发送 OLA (Open Look At)
     * 
     * @param target 目标
     * @param value 值
     */
    void sendOLA(String target, Object value);
}
