package dbclient.protocol;

import dbclient.media.model.PlaybackStatus;

/**
 * MA2 协议客户端接口
 * 
 * 职责：
 * - 建立与 MA2 服务器的连接（Telnet）
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
     * 连接到 MA2 服务器
     * 
     * @param host 主机地址
     * @param port 端口
     * @return 是否成功
     */
    boolean connect(String host, int port);

    /**
     * 登录
     * 
     * @param user 用户名
     * @param password 密码
     * @return 是否成功
     */
    boolean login(String user, String password);

    /**
     * 发送 MA2 命令
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
     * @param macro 宏名称或编号
     * @return 是否成功
     */
    boolean executeMacro(String macro);

    /**
     * 触发执行器
     * 
     * @param executor 执行器编号（如 "1.1"）
     * @return 是否成功
     */
    boolean triggerExecutor(String executor);

    /**
     * 设置通道参数
     * 
     * @param channel 通道
     * @param param 参数名
     * @param value 值
     * @return 是否成功
     */
    boolean setChannelParam(int channel, String param, Object value);
}
