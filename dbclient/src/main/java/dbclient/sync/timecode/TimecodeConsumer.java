package dbclient.sync.timecode;

/**
 * TimecodeConsumer - 时间码消费者接口
 * 
 * 实现者：LtcDriver, MtcDriver
 * 职责：接收 TimecodeCore 推送的当前帧，进行编码输出
 */
public interface TimecodeConsumer {
    
    /**
     * 接收当前帧
     * 
     * @param frame 当前时间码帧号（基于 FRAME_RATE）
     */
    void onFrame(long frame);
}
