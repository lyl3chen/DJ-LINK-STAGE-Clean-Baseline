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
public class LtcDriver implements OutputDriver, TimecodeConsumer, TimecodeCore.SourceChangeListener {
    
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
    private volatile String channelMode = "mono";  // mono, stereo-left, stereo-right, stereo-both
    private volatile long currentFrame = 0;
    
    // 音频输出
    private SourceDataLine audioLine;
    private AudioFormat audioFormat;
    
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
            if (config.get("channelMode") instanceof String) {
                channelMode = (String) config.get("channelMode");
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
        
        try {
            byte[] buffer = encodeFrame(frame);
            if (audioLine != null && audioLine.isOpen()) {
                audioLine.write(buffer, 0, buffer.length);
            }
        } catch (Exception e) {
            System.err.println("[LTC] Error: " + e.getMessage());
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
            "channelMode", channelMode,
            "currentFrame", currentFrame
        );
    }
    
    /**
     * 切源回调：只重置编码器相位，不重启音频设备
     */
    @Override
    public void onSourceChanged(int newPlayer) {
        System.out.println("[LTC] Source changed to player " + newPlayer);
        // 只重置 BMC 编码器相位，保持音频设备连续运行
        bmcEncoder.reset();
        System.out.println("[LTC] Encoder reset for new source");
    }
    
    // ============ 私有方法 ============
    
    private byte[] encodeFrame(long frame) {
        // 使用新的编码器接口
        boolean[] frameBits = frameEncoder.buildFrame(frame);
        double gain = Math.pow(10, gainDb / 20.0);
        byte[] monoPcm = bmcEncoder.encodeFrame(frameBits, gain);
        
        // 根据声道模式处理
        int channels = channelMode.equals("mono") ? 1 : 2;
        byte[] outputPcm;
        
        if (channels == 1) {
            // Mono: 直接返回
            outputPcm = monoPcm;
        } else {
            // Stereo: 需要扩展为双声道
            int monoSamples = monoPcm.length / 2;  // 16-bit samples
            outputPcm = new byte[monoSamples * 2 * 2];  // stereo = 2x size
            
            for (int i = 0; i < monoSamples; i++) {
                short sample = (short) ((monoPcm[i * 2 + 1] & 0xFF) << 8 | (monoPcm[i * 2] & 0xFF));
                short left = 0, right = 0;
                
                switch (channelMode) {
                    case "stereo-left":
                        left = sample;
                        right = 0;
                        break;
                    case "stereo-right":
                        left = 0;
                        right = sample;
                        break;
                    case "stereo-both":
                    default:
                        left = sample;
                        right = sample;
                        break;
                }
                
                // Left channel
                outputPcm[i * 4] = (byte) (left & 0xFF);
                outputPcm[i * 4 + 1] = (byte) ((left >> 8) & 0xFF);
                // Right channel
                outputPcm[i * 4 + 2] = (byte) (right & 0xFF);
                outputPcm[i * 4 + 3] = (byte) ((right >> 8) & 0xFF);
            }
        }
        
        return outputPcm;
    }
    
    private void openAudioDevice() throws Exception {
        int channels = channelMode.equals("mono") ? 1 : 2;
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, channels, true, false);
        this.audioFormat = format;
        
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        
        if ("default".equals(deviceName)) {
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
        } else {
            // 尝试匹配指定设备
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            for (Mixer.Info mixerInfo : mixerInfos) {
                if (mixerInfo.getName().contains(deviceName)) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    try {
                        audioLine = (SourceDataLine) mixer.getLine(info);
                        System.out.println("[LTC] Using device: " + mixerInfo.getName());
                        break;
                    } catch (Exception e) {
                        // 继续尝试下一个
                    }
                }
            }
            // 如果没找到匹配设备，使用默认
            if (audioLine == null) {
                audioLine = (SourceDataLine) AudioSystem.getLine(info);
                System.out.println("[LTC] Device not found, using default");
            }
        }
        
        audioLine.open(format);
        audioLine.start();
        System.out.println("[LTC] Opened " + channels + " channel(s), mode=" + channelMode);
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
