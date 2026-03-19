package dbclient.protocol;

/**
 * Titan 协议客户端接口
 * 
 * 职责：
 * - 建立与 Titan 服务器的连接
 * - 保持连接（心跳/保活）
 * - 重连机制
 * - 命令发送
 * - 响应处理
 * - 错误处理
 * 
 * 复用方：
 * - TitanBpmSyncDriver: 连续 BPM 推送
 * - TitanTriggerActionDriver: 事件触发动作
 */
public interface TitanClient extends ProtocolClient<TitanClient.ConnectionState> {

    /**
     * 连接状态
     */
    enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTHENTICATING,
        AUTHENTICATED,
        ERROR
    }

    /**
     * 发送 Titan 命令并获取响应
     * 
     * @param command Titan 命令
     * @return 响应
     */
    String sendTitanCommand(String command);

    /**
     * 发送 Titan 命令（简化版）
     * 
     * @param command 命令
     * @return 是否成功
     */
    boolean sendTitanCommandSimple(String command);

    /**
     * 设置参数
     * 
     * @param page 页面
     * @param button 按钮
     * @param value 值
     */
    void setButton(String page, String button, Object value);

    /**
     * 设置推子
     * 
     * @param channel 通道
     * @param value 值 (0-100)
     */
    void setFader(int channel, int value);

    /**
     * 发送 OSC 消息
     * 
     * @param path OSC 路径
     * @param args 参数
     */
    void sendOSC(String path, Object... args);
}
