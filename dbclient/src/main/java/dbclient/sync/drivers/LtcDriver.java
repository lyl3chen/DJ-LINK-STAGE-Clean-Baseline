package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.timecode.TimecodeConsumer;
import dbclient.sync.timecode.TimecodeCore;

import javax.sound.sampled.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LtcDriver - LTC 输出驱动（重构版）
 *
 * 核心改进：
 * - 发送线程内部维护严格的连续帧流
 * - 上层只提供状态/目标帧，不直接控制每帧输出
 * - 按音频缓冲区余量驱动持续填充
 * - 三态边界绝对稳定
 */
public class LtcDriver implements OutputDriver, TimecodeConsumer, TimecodeCore.SourceChangeListener, TimecodeCore.TimecodeStateListener {

    public static final String NAME = "ltc";
    private static final int SAMPLE_RATE = 48000;
    private static final double FRAME_RATE = 25.0;
    private static final int BITS_PER_FRAME = 80;
    private static final int BITS_PER_SECOND = (int) (FRAME_RATE * BITS_PER_FRAME);
    private static final int SAMPLES_PER_FRAME = SAMPLE_RATE / (int)FRAME_RATE; // 1920 @ 48k/25fps
    private static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2; // 16-bit mono = 3840 bytes

    // 编码器
    private final LtcFrameEncoder frameEncoder;
    private final LtcBmcEncoder bmcEncoder;

    // 状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean enabled = false;
    private volatile double gainDb = -8.0;
    private volatile String deviceName = "default";
    private volatile String channelMode = "mono";

    // 音频输出
    private SourceDataLine audioLine;
    private Thread audioThread;

    // ========== 发送状态机（新增）==========
    private enum TransportState {
        STOPPED,    // 输出稳定的 00:00:00:00
        PAUSED,     // 输出冻结的 heldFrame
        PLAYING     // 连续递增输出
    }

    private volatile TransportState transportState = TransportState.STOPPED;
    private volatile long heldFrame = 0;           // PAUSED 时冻结的帧
    private volatile long nextFrameToWrite = 0;    // PLAYING 时下一个要写的帧
    private volatile long targetAnchorFrame = 0;   // 上层提供的锚点帧（用于纠偏）
    private volatile long targetAnchorTimeNs = 0;  // 上层提供的锚点时间

    // 诊断（最小）
    private volatile long writeCount = 0;
    private volatile long writeBytes = 0;

    public LtcDriver() {
        this.frameEncoder = new LtcFrameEncoder(FRAME_RATE);
        this.bmcEncoder = new LtcBmcEncoder(SAMPLE_RATE, BITS_PER_SECOND);
    }

    @Override
    public String name() {
        return NAME;
    }

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

        System.out.println("[LTC] Enumerating audio devices for LTC output...");
        AudioDeviceEnumerator.enumerateOutputDevices();

        try {
            openAudioDevice();
        } catch (Exception e) {
            System.err.println("[LTC] Failed to open audio device '" + deviceName + "': " + e.getMessage());
            return;
        }

        // 初始化状态机
        transportState = TransportState.STOPPED;
        heldFrame = 0;
        nextFrameToWrite = 0;
        targetAnchorFrame = 0;
        targetAnchorTimeNs = System.nanoTime();

        running.set(true);
        audioThread = new Thread(this::audioLoop, "ltc-audio-writer");
        audioThread.setDaemon(true);
        audioThread.start();

        System.out.println("[LTC] Started with state machine: STOPPED -> output 00:00:00:00");
    }

    @Override
    public void stop() {
        if (!running.get()) return;

        running.set(false);
        if (audioThread != null) {
            audioThread.interrupt();
            try {
                audioThread.join(200);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }
        closeAudioDevice();
    }

    @Override
    public void update(Map<String, Object> state) {
        // 不处理上层状态（由 onFrame 处理）
    }

    /**
     * TimecodeCore 推送回调：提供状态和目标帧信息
     * 注意：只更新状态机目标，不直接控制每帧输出
     */
    @Override
    public void onFrame(long frame) {
        if (!running.get() || !enabled) return;

        // 帧号 < 0 视为停止
        if (frame < 0) {
            setTransportState(TransportState.STOPPED);
            return;
        }

        // 根据 TimecodeCore 状态决定 transportState
        // 这里简化处理：frame == 0 且未运行视为 STOPPED，否则 PLAYING
        // 实际应由上层明确告知状态，但这里用帧号变化推断
    }

    /**
     * 设置传输状态（由上层调用）
     */
    public synchronized void setTransportState(TransportState newState) {
        if (this.transportState == newState) return;

        TransportState oldState = this.transportState;
        this.transportState = newState;

        switch (newState) {
            case STOPPED:
                // STOPPED: 固定输出 00:00:00:00
                nextFrameToWrite = 0;
                heldFrame = 0;
                System.out.println("[LTC] State: STOPPED -> output 00:00:00:00");
                break;

            case PAUSED:
                // PAUSED: 冻结当前帧
                heldFrame = nextFrameToWrite;
                System.out.println("[LTC] State: PAUSED -> freeze frame " + heldFrame);
                break;

            case PLAYING:
                // PLAYING: 从当前位置继续
                if (oldState == TransportState.STOPPED) {
                    nextFrameToWrite = 0;
                }
                // 如果从 PAUSED 恢复，nextFrameToWrite 已经是正确值
                System.out.println("[LTC] State: PLAYING -> resume from frame " + nextFrameToWrite);
                break;
        }
    }

    /**
     * 更新锚点（由上层定期纠偏）
     */
    public synchronized void updateAnchor(long anchorFrame, long anchorTimeNs) {
        this.targetAnchorFrame = anchorFrame;
        this.targetAnchorTimeNs = anchorTimeNs;

        // 只有在 PLAYING 状态下才需要纠偏
        if (transportState == TransportState.PLAYING) {
            // 计算预期帧（基于本地时钟）
            long elapsedNs = System.nanoTime() - anchorTimeNs;
            long elapsedFrames = (long) (elapsedNs * FRAME_RATE / 1_000_000_000.0);
            long expectedFrame = anchorFrame + elapsedFrames;

            // 偏差过大时调整 nextFrameToWrite
            long diff = Math.abs(nextFrameToWrite - expectedFrame);
            if (diff > 5) { // 超过 5 帧（200ms）才纠偏
                nextFrameToWrite = expectedFrame;
                System.out.println("[LTC] Drift correction: adjusted to frame " + nextFrameToWrite);
            }
        }
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("running", running.get());
        out.put("sampleRate", SAMPLE_RATE);
        out.put("fps", FRAME_RATE);
        out.put("gainDb", gainDb);
        out.put("deviceName", deviceName);
        out.put("channelMode", channelMode);

        // 状态机信息
        out.put("transportState", transportState.name());
        out.put("heldFrame", heldFrame);
        out.put("nextFrameToWrite", nextFrameToWrite);
        out.put("targetAnchorFrame", targetAnchorFrame);

        out.put("writeCount", writeCount);
        out.put("writeBytes", writeBytes);

        return out;
    }

    @Override
    public void onSourceChanged(int newPlayer) {
        System.out.println("[LTC] Source changed to player " + newPlayer);
        bmcEncoder.reset();
    }

    @Override
    public void onStateChange(String fromState, String toState, long anchorFrame, long anchorTimeNs) {
        System.out.println("[LTC] State change: " + fromState + " -> " + toState);
        
        TransportState newTransportState;
        switch (toState) {
            case "PLAYING":
                newTransportState = TransportState.PLAYING;
                break;
            case "PAUSED":
                newTransportState = TransportState.PAUSED;
                break;
            case "STOPPED":
            default:
                newTransportState = TransportState.STOPPED;
                break;
        }
        
        setTransportState(newTransportState);
        
        // 更新锚点（用于 PLAYING 状态下的纠偏）
        if ("PLAYING".equals(toState)) {
            updateAnchor(anchorFrame, anchorTimeNs);
        }
    }

    // ========== 核心发送循环（重构）==========

    private void audioLoop() {
        // 预分配编码缓冲（避免每帧 GC）
        byte[] monoPcm = new byte[BYTES_PER_FRAME];

        while (running.get()) {
            try {
                SourceDataLine line = audioLine;
                if (line == null || !line.isOpen()) {
                    Thread.sleep(2);
                    continue;
                }

                // 按音频线可写空间持续填充
                int available = line.available();
                int framesToWrite = available / BYTES_PER_FRAME;

                if (framesToWrite <= 0) {
                    // 缓冲满，稍等
                    Thread.sleep(1);
                    continue;
                }

                // 连续写多帧
                for (int i = 0; i < framesToWrite && running.get(); i++) {
                    long frameToEncode = getCurrentFrameToWrite();
                    encodeFrameToBuffer(frameToEncode, monoPcm);

                    // 声道处理
                    byte[] outputPcm = applyChannelMode(monoPcm);

                    line.write(outputPcm, 0, outputPcm.length);
                    writeCount++;
                    writeBytes += outputPcm.length;
                }

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                System.err.println("[LTC] audioLoop error: " + e.getMessage());
            }
        }
    }

    /**
     * 根据当前状态机获取要写的帧号
     */
    private long getCurrentFrameToWrite() {
        switch (transportState) {
            case STOPPED:
                // STOPPED: 始终输出 0
                return 0;

            case PAUSED:
                // PAUSED: 冻结帧
                return heldFrame;

            case PLAYING:
                // PLAYING: 递增并返回
                long frame = nextFrameToWrite;
                nextFrameToWrite++;
                return frame;

            default:
                return 0;
        }
    }

    /**
     * 编码指定帧到缓冲（复用 buffer）
     */
    private void encodeFrameToBuffer(long frame, byte[] outBuffer) {
        boolean[] frameBits = frameEncoder.buildFrame(frame);
        double gain = Math.pow(10, gainDb / 20.0);

        // BMC 编码
        int amplitude = (int) Math.round(16422.0 * gain);
        int samplesPerBit = SAMPLE_RATE / BITS_PER_SECOND; // 24
        int samplesPerHalfBit = samplesPerBit / 2; // 12

        int p = 0;
        boolean level = true; // 简化：每帧重置相位（或保持连续相位）

        for (int i = 0; i < BITS_PER_FRAME; i++) {
            boolean bit = frameBits[i];

            // bit start transition
            level = !level;
            short firstHalf = (short) (level ? amplitude : -amplitude);
            for (int j = 0; j < samplesPerHalfBit; j++) {
                outBuffer[p++] = (byte) (firstHalf & 0xFF);
                outBuffer[p++] = (byte) ((firstHalf >> 8) & 0xFF);
            }

            // mid-bit transition only for bit=1
            if (bit) {
                level = !level;
            }
            short secondHalf = (short) (level ? amplitude : -amplitude);
            for (int j = 0; j < samplesPerHalfBit; j++) {
                outBuffer[p++] = (byte) (secondHalf & 0xFF);
                outBuffer[p++] = (byte) ((secondHalf >> 8) & 0xFF);
            }
        }
    }

    /**
     * 应用声道模式
     */
    private byte[] applyChannelMode(byte[] monoPcm) {
        if ("mono".equals(channelMode)) {
            return monoPcm;
        }

        int monoSamples = monoPcm.length / 2;
        byte[] outputPcm = new byte[monoSamples * 4];

        for (int i = 0; i < monoSamples; i++) {
            short sample = (short) (((monoPcm[i * 2 + 1] & 0xFF) << 8) | (monoPcm[i * 2] & 0xFF));
            short left = 0;
            short right = 0;

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

            outputPcm[i * 4] = (byte) (left & 0xFF);
            outputPcm[i * 4 + 1] = (byte) ((left >> 8) & 0xFF);
            outputPcm[i * 4 + 2] = (byte) (right & 0xFF);
            outputPcm[i * 4 + 3] = (byte) ((right >> 8) & 0xFF);
        }

        return outputPcm;
    }

    private void openAudioDevice() throws Exception {
        int channels = channelMode.equals("mono") ? 1 : 2;
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, channels, true, false);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        if ("default".equals(deviceName)) {
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
        } else {
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            for (Mixer.Info mixerInfo : mixerInfos) {
                if (mixerInfo.getName().contains(deviceName)) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    try {
                        audioLine = (SourceDataLine) mixer.getLine(info);
                        System.out.println("[LTC] Using device: " + mixerInfo.getName());
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (audioLine == null) {
                audioLine = (SourceDataLine) AudioSystem.getLine(info);
            }
        }

        // 较大的缓冲以支持连续流
        int bufferSize = BYTES_PER_FRAME * 4 * channels; // 4 帧缓冲
        audioLine.open(format, bufferSize);
        audioLine.start();
        System.out.println("[LTC] Opened " + channels + " channel(s), mode=" + channelMode + ", buffer=" + audioLine.getBufferSize());
    }

    private void closeAudioDevice() {
        if (audioLine != null) {
            try {
                audioLine.stop();
                audioLine.close();
            } catch (Exception ignored) {
            }
            audioLine = null;
        }
    }
}
