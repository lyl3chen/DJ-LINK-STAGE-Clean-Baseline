package dbclient.protocol;

/**
 * 协议客户端基础接口
 * 
 * 职责：
 * - 建立连接
 * - 保持连接（心跳/保活）
 * - 重连机制
 * - 命令发送
 * - 响应处理
 * - 错误处理
 * 
 * @param <C> 连接状态类型
 */
public interface ProtocolClient<C> {

    /**
     * 获取客户端名称
     */
    String getName();

    /**
     * 检查是否已连接
     */
    boolean isConnected();

    /**
     * 连接到服务器
     * 
     * @return 是否成功
     */
    boolean connect();

    /**
     * 断开连接
     */
    void disconnect();

    /**
     * 发送命令
     * 
     * @param command 命令内容
     * @return 响应结果
     */
    String sendCommand(String command);

    /**
     * 发送命令（带超时）
     * 
     * @param command 命令内容
     * @param timeoutMs 超时毫秒
     * @return 响应结果
     */
    String sendCommand(String command, long timeoutMs);

    /**
     * 获取连接状态
     */
    C getConnectionState();

    /**
     * 获取统计信息
     */
    java.util.Map<String, Object> getStats();
}
