package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.timecode.TimecodeConsumer;
import dbclient.sync.timecode.TimecodeCore;

import javax.sound.sampled.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LtcDriver - LTC 输出驱动
 *
 * 职责：
 * - 作为 TimecodeConsumer 接收 TimecodeCore 推送的帧
 * - 将帧编码为 LTC 音频信号输出
 * - 在本类内部完成实时音频写入与最小诊断
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
    private Thread audioThread;

    // onFrame -> audioThread 共享帧（冻结快照：每次只传 long frame）
    private volatile long latestFrame = 0;
    private volatile long lastWrittenFrame = -1;

    // 诊断（最小）
    private volatile long writeCount = 0;
    private volatile long writeBytes = 0;
    private volatile long underrunCount = 0;
    private volatile long starvationCount = 0;

    private volatile long writeIntervalCount = 0;
    private volatile long writeIntervalMinNs = Long.MAX_VALUE;
    private volatile long writeIntervalMaxNs = 0;
    private volatile long writeIntervalSumNs = 0;
    private volatile long lastWriteNs = 0;

    private volatile long frameDeltaCount = 0;
    private volatile long frameDeltaMin = Long.MAX_VALUE;
    private volatile long frameDeltaMax = Long.MIN_VALUE;
    private volatile long frameDeltaAnomalyCount = 0; // 期望 1，其他计为异常

    private volatile int lineBufferBytes = 0;
    private volatile int occupancyMinBytes = Integer.MAX_VALUE;
    private volatile int occupancyMaxBytes = 0;
    private volatile long occupancySamples = 0;
    private volatile long occupancySumBytes = 0;

    // stereo-left 校验
    private volatile long stereoLeftCheckSamples = 0;
    private volatile long stereoLeftRightNonZeroSamples = 0;

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

        resetDiagnostics();

        running.set(true);
        audioThread = new Thread(this::audioLoop, "ltc-audio-writer");
        audioThread.setDaemon(true);
        audioThread.start();
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
        // 不处理上层状态
    }

    /**
     * TimecodeCore 推送回调：只更新最新帧，不做阻塞写音频。
     */
    @Override
    public void onFrame(long frame) {
        if (!running.get() || !enabled) return;
        this.currentFrame = frame;
        this.latestFrame = frame;
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
        out.put("currentFrame", currentFrame);

        out.put("samplesPerBit", SAMPLE_RATE / BITS_PER_SECOND); // 25fps=24
        out.put("halfBitSamples", (SAMPLE_RATE / BITS_PER_SECOND) / 2); // 12

        out.put("lineBufferBytes", lineBufferBytes);
        out.put("writeCount", writeCount);
        out.put("writeBytes", writeBytes);
        out.put("underrunCount", underrunCount);
        out.put("starvationCount", starvationCount);

        Map<String, Object> writeDiag = new LinkedHashMap<>();
        writeDiag.put("intervalMinMs", nsToMs(writeIntervalMinNs == Long.MAX_VALUE ? 0 : writeIntervalMinNs));
        writeDiag.put("intervalMaxMs", nsToMs(writeIntervalMaxNs));
        writeDiag.put("intervalAvgMs", writeIntervalCount > 0 ? nsToMs(writeIntervalSumNs / writeIntervalCount) : 0.0);
        writeDiag.put("intervalSamples", writeIntervalCount);
        out.put("writeInterval", writeDiag);

        Map<String, Object> occ = new LinkedHashMap<>();
        occ.put("minBytes", occupancyMinBytes == Integer.MAX_VALUE ? 0 : occupancyMinBytes);
        occ.put("maxBytes", occupancyMaxBytes);
        occ.put("avgBytes", occupancySamples > 0 ? (occupancySumBytes / occupancySamples) : 0);
        occ.put("samples", occupancySamples);
        out.put("bufferOccupancy", occ);

        Map<String, Object> frameDiag = new LinkedHashMap<>();
        frameDiag.put("lastWrittenFrame", lastWrittenFrame);
        frameDiag.put("deltaMin", frameDeltaMin == Long.MAX_VALUE ? 0 : frameDeltaMin);
        frameDiag.put("deltaMax", frameDeltaMax == Long.MIN_VALUE ? 0 : frameDeltaMax);
        frameDiag.put("deltaSamples", frameDeltaCount);
        frameDiag.put("deltaAnomalyCount", frameDeltaAnomalyCount);
        out.put("frameDelta", frameDiag);

        if ("stereo-left".equals(channelMode)) {
            Map<String, Object> stereoDiag = new LinkedHashMap<>();
            stereoDiag.put("checkedFrames", stereoLeftCheckSamples);
            stereoDiag.put("rightNonZeroFrames", stereoLeftRightNonZeroSamples);
            out.put("stereoLeftCheck", stereoDiag);
        }

        return out;
    }

    @Override
    public void onSourceChanged(int newPlayer) {
        System.out.println("[LTC] Source changed to player " + newPlayer);
        bmcEncoder.reset();
    }

    private void audioLoop() {
        while (running.get()) {
            try {
                SourceDataLine line = audioLine;
                if (line == null || !line.isOpen()) {
                    starvationCount++;
                    Thread.sleep(2);
                    continue;
                }

                long frame = latestFrame;
                byte[] buffer = encodeFrame(frame);

                int availableBefore = line.available();
                int bufferBytes = line.getBufferSize();
                lineBufferBytes = bufferBytes;
                int occupancyBefore = Math.max(0, bufferBytes - availableBefore);
                recordOccupancy(occupancyBefore);

                // 若几乎见底，视作一次 underrun 风险
                if (occupancyBefore <= (buffer.length / 2)) {
                    underrunCount++;
                }

                long nowNs = System.nanoTime();
                if (lastWriteNs != 0) {
                    long dt = nowNs - lastWriteNs;
                    writeIntervalCount++;
                    writeIntervalSumNs += dt;
                    if (dt < writeIntervalMinNs) writeIntervalMinNs = dt;
                    if (dt > writeIntervalMaxNs) writeIntervalMaxNs = dt;
                }
                lastWriteNs = nowNs;

                if (lastWrittenFrame >= 0) {
                    long delta = frame - lastWrittenFrame;
                    frameDeltaCount++;
                    if (delta < frameDeltaMin) frameDeltaMin = delta;
                    if (delta > frameDeltaMax) frameDeltaMax = delta;
                    if (delta != 1) frameDeltaAnomalyCount++;
                }
                lastWrittenFrame = frame;

                if ("stereo-left".equals(channelMode)) {
                    verifyStereoLeftFrame(buffer);
                }

                int written = line.write(buffer, 0, buffer.length);
                writeCount++;
                writeBytes += written;

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                starvationCount++;
                System.err.println("[LTC] audioLoop error: " + e.getMessage());
            }
        }
    }

    private void resetDiagnostics() {
        writeCount = 0;
        writeBytes = 0;
        underrunCount = 0;
        starvationCount = 0;

        writeIntervalCount = 0;
        writeIntervalMinNs = Long.MAX_VALUE;
        writeIntervalMaxNs = 0;
        writeIntervalSumNs = 0;
        lastWriteNs = 0;

        frameDeltaCount = 0;
        frameDeltaMin = Long.MAX_VALUE;
        frameDeltaMax = Long.MIN_VALUE;
        frameDeltaAnomalyCount = 0;

        lineBufferBytes = 0;
        occupancyMinBytes = Integer.MAX_VALUE;
        occupancyMaxBytes = 0;
        occupancySamples = 0;
        occupancySumBytes = 0;

        stereoLeftCheckSamples = 0;
        stereoLeftRightNonZeroSamples = 0;

        lastWrittenFrame = -1;
        latestFrame = 0;
        currentFrame = 0;
    }

    private void recordOccupancy(int occBytes) {
        occupancySamples++;
        occupancySumBytes += occBytes;
        if (occBytes < occupancyMinBytes) occupancyMinBytes = occBytes;
        if (occBytes > occupancyMaxBytes) occupancyMaxBytes = occBytes;
    }

    private void verifyStereoLeftFrame(byte[] stereoBuffer) {
        // 每帧抽检一次：检查右声道采样是否全0
        // stereoBuffer: [Llo,Lhi,Rlo,Rhi] * N
        stereoLeftCheckSamples++;
        for (int i = 0; i + 3 < stereoBuffer.length; i += 4) {
            short right = (short) (((stereoBuffer[i + 3] & 0xFF) << 8) | (stereoBuffer[i + 2] & 0xFF));
            if (right != 0) {
                stereoLeftRightNonZeroSamples++;
                return;
            }
        }
    }

    private byte[] encodeFrame(long frame) {
        boolean[] frameBits = frameEncoder.buildFrame(frame);
        double gain = Math.pow(10, gainDb / 20.0);
        byte[] monoPcm = bmcEncoder.encodeFrame(frameBits, gain);

        int channels = channelMode.equals("mono") ? 1 : 2;
        if (channels == 1) {
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
        this.audioFormat = format;

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
                System.out.println("[LTC] Device not found, using default");
            }
        }

        // 固定较小缓冲，减小时基抖动放大效应（约 2 帧）
        int frameBytes = channels == 1 ? 3840 : 7680; // 25fps 一帧
        int desiredBuffer = frameBytes * 2;
        audioLine.open(format, desiredBuffer);
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

    private double nsToMs(long ns) {
        return ns / 1_000_000.0;
    }
}
