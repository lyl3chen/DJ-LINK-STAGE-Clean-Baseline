package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.timecode.TimecodeConsumer;
import dbclient.sync.timecode.TimecodeCore;

import javax.sound.sampled.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LtcDriver - LTC 音频时间码输出 (FINAL - 已稳定)
 *
 * 【核心架构】
 * 1. 本地帧推进：nextFrameToWrite 由音频线程独立维护
 * 2. 事件重锚：onFrame() 检测跳变，diff > 50 帧时重锚
 * 3. 状态同步：onStateChange() 处理 STOPPED/PLAYING/PAUSED
 * 4. 音频驱动：audioLoop() 按缓冲区余量持续填充
 *
 * 【与 MTC 差异】
 * - LTC：音频输出，需要音频线程平滑填充
 * - MTC：MIDI 输出，burst 发送 8 个 QF
 */
public class LtcDriver implements OutputDriver, TimecodeConsumer, TimecodeCore.TimecodeStateListener {

    public static final String NAME = "ltc";
    private static final int SAMPLE_RATE = 48000;
    private static final double FRAME_RATE = 25.0;
    private static final int SAMPLES_PER_FRAME = SAMPLE_RATE / (int)FRAME_RATE; // 1920
    private static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2; // 3840 bytes (16-bit mono)
    private static final long JUMP_THRESHOLD_FRAMES = 50; // 2秒跳变阈值

    // 编码器（BMC + LTC 帧格式）
    private final LtcFrameEncoder frameEncoder;
    private final LtcBmcEncoder bmcEncoder;

    // 运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean enabled = false;
    private volatile double gainDb = -8.0;
    private volatile String deviceName = "default";
    private volatile String channelMode = "mono";

    // 音频输出
    private SourceDataLine audioLine;
    private Thread audioThread;

    // 传输状态机
    private enum TransportState { STOPPED, PLAYING, PAUSED }
    private volatile TransportState transportState = TransportState.STOPPED;

    // 本地帧推进状态（核心）
    private volatile long nextFrameToWrite = 0;    // 下一个要写入的帧
    private volatile long lastAnchorFrame = 0;     // 上次锚点（用于跳变检测）

    public LtcDriver() {
        this.frameEncoder = new LtcFrameEncoder(FRAME_RATE);
        this.bmcEncoder = new LtcBmcEncoder(SAMPLE_RATE, (int)(FRAME_RATE * 80));
    }

    @Override
    public String name() { return NAME; }

    @Override
    public void start(Map<String, Object> config) {
        if (running.get()) return;

        if (config != null) {
            enabled = Boolean.TRUE.equals(config.get("enabled"));
            if (config.get("gainDb") instanceof Number) gainDb = ((Number) config.get("gainDb")).doubleValue();
            if (config.get("deviceName") instanceof String) deviceName = (String) config.get("deviceName");
            if (config.get("channelMode") instanceof String) channelMode = (String) config.get("channelMode");
        }
        if (!enabled) return;

        try { openAudioDevice(); }
        catch (Exception e) { System.err.println("[LTC] Failed to open: " + e.getMessage()); return; }

        transportState = TransportState.STOPPED;
        nextFrameToWrite = 0;
        lastAnchorFrame = 0;
        running.set(true);

        audioThread = new Thread(this::audioLoop, "ltc-audio-writer");
        audioThread.setDaemon(true);
        audioThread.start();

        System.out.println("[LTC] Started");
    }

    @Override
    public void stop() {
        if (!running.get()) return;
        running.set(false);
        if (audioThread != null) {
            audioThread.interrupt();
            try { audioThread.join(200); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        closeAudioDevice();
    }

    @Override
    public void update(Map<String, Object> state) {
        // 状态由 onStateChange 处理，不在这里处理
    }

    /**
     * TimecodeCore 回调：均匀推送（25fps）
     * 【核心逻辑】检测跳变并重锚，不直接控制每帧输出
     */
    @Override
    public void onFrame(long frame) {
        if (!running.get() || !enabled) return;

        // 只在 PLAYING 状态检测跳变
        if (transportState != TransportState.PLAYING) return;

        long diff = Math.abs(frame - lastAnchorFrame);
        if (diff > JUMP_THRESHOLD_FRAMES) {
            System.out.println("[LTC] Jump: " + lastAnchorFrame + " -> " + frame);
            nextFrameToWrite = frame;
        }
        lastAnchorFrame = frame;
    }

    /**
     * 状态变化回调
     * 【关键】状态转换时重置本地帧状态
     */
    @Override
    public void onStateChange(String from, String to, long anchorFrame, long timeNs) {
        System.out.println("[LTC] State: " + from + " -> " + to);

        switch (to) {
            case "PLAYING":
                transportState = TransportState.PLAYING;
                nextFrameToWrite = anchorFrame;
                lastAnchorFrame = anchorFrame;
                break;
            case "STOPPED":
                transportState = TransportState.STOPPED;
                nextFrameToWrite = 0;
                lastAnchorFrame = 0;
                break;
            case "PAUSED":
                transportState = TransportState.PAUSED;
                // 保持当前帧位置，不再推进
                break;
        }
    }

    /**
     * 音频写入线程（核心）
     * 【关键】按音频缓冲区余量持续填充，本地线性推进
     */
    private void audioLoop() {
        byte[] monoPcm = new byte[BYTES_PER_FRAME];

        while (running.get()) {
            try {
                SourceDataLine line = audioLine;
                if (line == null || !line.isOpen()) { Thread.sleep(2); continue; }

                int available = line.available();
                int framesToWrite = available / BYTES_PER_FRAME;
                if (framesToWrite <= 0) { Thread.sleep(1); continue; }

                for (int i = 0; i < framesToWrite && running.get(); i++) {
                    long frameToEncode = getCurrentFrameToWrite();
                    encodeFrameToBuffer(frameToEncode, monoPcm);
                    byte[] outputPcm = applyChannelMode(monoPcm);
                    line.write(outputPcm, 0, outputPcm.length);
                }
            } catch (Exception e) {
                // 音频错误，短暂休眠后重试
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * 获取当前要写入的帧（本地推进）
     * 【关键】PAUSED 时返回同一帧，PLAYING 时递增
     */
    private long getCurrentFrameToWrite() {
        if (transportState == TransportState.STOPPED) return 0;

        long frame = nextFrameToWrite;

        // PAUSED 时不递增，PLAYING 时递增
        if (transportState == TransportState.PLAYING) {
            nextFrameToWrite++;
        }

        return frame;
    }

    private void encodeFrameToBuffer(long frame, byte[] buffer) {
        boolean[] frameBits = frameEncoder.buildFrame(frame);
        double gain = Math.pow(10, gainDb / 20.0);
        byte[] encoded = bmcEncoder.encodeFrame(frameBits, gain);
        System.arraycopy(encoded, 0, buffer, 0, Math.min(encoded.length, buffer.length));
    }

    private byte[] applyChannelMode(byte[] mono) {
        if ("stereo".equals(channelMode)) {
            byte[] stereo = new byte[mono.length * 2];
            for (int i = 0; i < mono.length; i += 2) {
                stereo[i * 2] = mono[i];
                stereo[i * 2 + 1] = mono[i + 1];
                stereo[i * 2 + 2] = mono[i];
                stereo[i * 2 + 3] = mono[i + 1];
            }
            return stereo;
        }
        return mono;
    }

    private void openAudioDevice() throws Exception {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, "stereo".equals(channelMode) ? 2 : 1, true, false);
        
        // 使用 AudioDeviceEnumerator 获取指定设备的 line
        audioLine = AudioDeviceEnumerator.getSourceDataLine(deviceName, format);
        
        if (audioLine == null) {
            throw new Exception("Failed to open audio device: " + deviceName);
        }
        
        audioLine.open(format);
        audioLine.start();
        
        System.out.println("[LTC] Opened audio device: " + (deviceName != null ? deviceName : "default"));
    }

    private void closeAudioDevice() {
        if (audioLine != null) {
            audioLine.stop();
            audioLine.close();
            audioLine = null;
        }
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("running", running.get());
        out.put("transportState", transportState.name());
        out.put("nextFrameToWrite", nextFrameToWrite);
        out.put("sampleRate", SAMPLE_RATE);
        out.put("fps", FRAME_RATE);
        return out;
    }
}
