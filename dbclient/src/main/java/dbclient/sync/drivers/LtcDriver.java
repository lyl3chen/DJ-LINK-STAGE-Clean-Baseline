package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.timecode.TimecodeConsumer;
import dbclient.sync.timecode.TimecodeCore;

import javax.sound.sampled.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LtcDriver - LTC 输出驱动
 * 
 * 职责：
 * - 作为 TimecodeConsumer 接收 TimecodeCore 推送的帧
 * - 将帧编码为 LTC 音频信号输出
 * 
 * 不处理：
 * - 播放源选择（由 TimecodeCore 处理）
 * - 事件检测（由 TimecodeCore 处理）
 * - 锚点管理（由 TimecodeCore 处理）
 */
public class LtcDriver implements OutputDriver, TimecodeConsumer {
    
    public static final String NAME = "ltc";
    private static final int SAMPLE_RATE = 48000;
    private static final double FRAME_RATE = 25.0;
    private static final int BITS_PER_FRAME = 80;
    private static final int BITS_PER_SECOND = (int) (FRAME_RATE * BITS_PER_FRAME);
    
    // 编码器
    private final LtcFrameEncoder frameEncoder;
    private final LtcBmcEncoder bmcEncoder;
    
    // 状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean enabled = false;
    private volatile double gainDb = -8.0;
    private volatile String deviceName = "default";
    private volatile long currentFrame = 0;
    
    // 音频输出
    private SourceDataLine audioLine;
    
    public LtcDriver() {
        this.frameEncoder = new LtcFrameEncoder(FRAME_RATE);
        this.bmcEncoder = new LtcBmcEncoder(SAMPLE_RATE, BITS_PER_SECOND);
    }
    
    @Override
    public String name() {
        return NAME;
    }
    
    /**
     * 启动驱动
     */
    @Override
    public void start(Map<String, Object> config) {
        if (running.get()) return;
        
        if (config != null) {
            enabled = Boolean.TRUE.equals(config.get("enabled"));
            if (config.get("gainDb") instanceof Number) {
                gainDb = ((Number) config.get("gainDb")).doubleValue();
            }
            if (config.get("deviceName") instanceof String) {
                deviceName = (String) config.get("deviceName");
            }
        }
        
        if (!enabled) return;
        
        // 枚举并打印所有可用音频设备
        System.out.println("[LTC] Enumerating audio devices for LTC output...");
        AudioDeviceEnumerator.enumerateOutputDevices();
        
        // 打开音频设备
        try {
            openAudioDevice();
        } catch (Exception e) {
            System.err.println("[LTC] Failed to open audio device '" + deviceName + "': " + e.getMessage());
            return;
        }
        
        running.set(true);
    }
    
    /**
     * 停止驱动
     */
    @Override
    public void stop() {
        if (!running.get()) return;
        
        running.set(false);
        closeAudioDevice();
    }
    
    /**
     * 接收播放器状态（传递给 TimecodeCore）
     * 
     * 注意：此方法只传递状态，不作为输出节奏
     */
    @Override
    public void update(Map<String, Object> state) {
        // LtcDriver 不直接处理状态
        // 状态由 TimecodeCore 处理，LtcDriver 只接收 onFrame 回调
        // 此方法保留以满足 OutputDriver 接口
    }
    
    /**
     * TimecodeConsumer 回调：接收当前帧并输出
     * 
     * 由 TimecodeCore 以均匀节奏（25fps）调用
     */
    @Override
    public void onFrame(long frame) {
        if (!running.get() || !enabled) return;
        
        this.currentFrame = frame;
        
        // 编码并输出
        try {
            byte[] buffer = encodeFrame(frame);
            if (audioLine != null && audioLine.isOpen()) {
                audioLine.write(buffer, 0, buffer.length);
            }
        } catch (Exception e) {
            System.err.println("[LTC] Output error: " + e.getMessage());
        }
    }
    
    /**
     * 获取驱动状态
     */
    @Override
    public Map<String, Object> status() {
        return Map.of(
            "enabled", enabled,
            "running", running.get(),
            "sampleRate", SAMPLE_RATE,
            "fps", FRAME_RATE,
            "gainDb", gainDb,
            "deviceName", deviceName,
            "currentFrame", currentFrame
        );
    }
    
    // ============ 私有方法 ============
    
    private byte[] encodeFrame(long frame) {
        // 构建 80-bit LTC 帧
        boolean[] frameBits = frameEncoder.buildFrame(frame);
        
        // 计算缓冲区大小
        int samplesPerFrame = SAMPLE_RATE / (int) FRAME_RATE;
        int samplesPerBit = SAMPLE_RATE / BITS_PER_SECOND;
        byte[] buffer = new byte[samplesPerFrame * 2];  // 16-bit PCM
        
        double gain = Math.pow(10, gainDb / 20.0);
        int sampleIndex = 0;
        int bitIndex = 0;
        
        for (int i = 0; i < samplesPerFrame; i++) {
            if (bitIndex < 80) {
                boolean bit = frameBits[bitIndex];
                double sample = bmcEncoder.nextSample(bit) * gain;
                int sampleInt = (int) (sample * 32767);
                buffer[sampleIndex++] = (byte) (sampleInt & 0xFF);
                buffer[sampleIndex++] = (byte) ((sampleInt >> 8) & 0xFF);
            } else {
                buffer[sampleIndex++] = 0;
                buffer[sampleIndex++] = 0;
            }
            
            if ((i + 1) % samplesPerBit == 0) {
                bitIndex++;
            }
        }
        
        return buffer;
    }
    
    private void openAudioDevice() throws Exception {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        
        if ("default".equals(deviceName)) {
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
        } else {
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
        }
        
        audioLine.open(format);
        audioLine.start();
    }
    
    private void closeAudioDevice() {
        if (audioLine != null) {
            try {
                audioLine.stop();
                audioLine.close();
            } catch (Exception ignored) {}
            audioLine = null;
        }
    }
}
