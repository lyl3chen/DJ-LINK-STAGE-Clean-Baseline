package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeClock;

import javax.sound.sampled.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal LTC Driver - Simplified for pitch=0 only + hot cue support
 * 
 * Capabilities:
 * 1. Fixed rate output at pitch=0 (uniform, stable LTC)
 * 2. Hot cue reanchor support
 * 
 * Removed: rate smoothing, loop, media change, pause/resume complexity
 */
public class LtcDriver implements OutputDriver {
    private volatile boolean running;
    private volatile double seconds;
    private volatile int frameInSecond;
    private volatile double signalLevel = 0.0;
    private volatile String activeDevice = "-";
    private volatile String lastError = "";
    private volatile String outputState = "IDLE";

    // Phase A: 可解释性状态（不参与选路决策）
    private volatile String configuredTarget = "default";
    private volatile String matchedDevice = "-";
    private volatile String matchMode = "fallback";
    private volatile int matchScore = 0;
    private volatile String endpointType = "unknown";
    private volatile String channelRole = "unknown";
    private volatile boolean deviceOpenable = false;
    private volatile String warning = "";
    private volatile String lastSuccessfulDevice = "-";

    private volatile boolean sourcePlaying = false;
    private volatile boolean sourceActive = false;
    private volatile String sourceState = "OFFLINE";
    private volatile long lastSourceUpdateMs = 0L;
    private volatile TimecodeClock clock;

    // 简化的位置状态（用于 hot cue、换歌、restart 检测）
    private volatile long lastPositionMs = 0L;
    private volatile long reanchorCount = 0L;
    // 换歌检测
    private volatile String currentTrackId = "";
    private volatile String currentRekordboxId = "";
    private volatile int currentPlayerId = 0;
    // stop/restart 检测
    private volatile boolean wasPlaying = false;
    // 一次性重锚标志位
    private volatile boolean pendingReanchor = false;
    private volatile long pendingReanchorTargetMs = 0L;
    private volatile String pendingReanchorReason = "";

    private volatile boolean blockClockInit = false;
    private volatile double nextBlockStartSec = 0.0;
    private volatile long localLtcSamplePosition = 0L;
    private volatile long localLtcFramePosition = 0L;
    private volatile long nextFrameBoundarySample = 0L;
    private volatile boolean framePrimed = false;
    private volatile boolean initialAligned = false;

    private Map<String, Object> cfg = new LinkedHashMap<>();

    private SourceDataLine line = null;

    @Override
    public String name() { return "ltc"; }

    @Override
    public void start(Map<String, Object> cfg) {
        this.cfg = new LinkedHashMap<>(cfg);
        if (Boolean.TRUE.equals(cfg.get("enabled"))) {
            startLtc();
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void update(Map<String, Object> state) {
        if (!running || state == null) return;

        Object sp = state.get("sourcePlaying");
        Object sa = state.get("sourceActive");
        sourcePlaying = Boolean.TRUE.equals(sp);
        sourceActive = Boolean.TRUE.equals(sa);
        sourceState = String.valueOf(state.getOrDefault("sourceState", "OFFLINE"));
        lastSourceUpdateMs = System.currentTimeMillis();

        // 捕获位置
        Object ctMs = state.get("currentTimeMs");
        long newPositionMs = 0L;
        if (ctMs instanceof Number) {
            newPositionMs = ((Number) ctMs).longValue();
        }

        // === 换歌检测 ===
        String newTrackId = String.valueOf(state.getOrDefault("trackId", ""));
        String newRekordboxId = String.valueOf(state.getOrDefault("rekordboxId", ""));
        int newPlayerId = 0;
        if (state.get("playerId") instanceof Number) {
            newPlayerId = ((Number) state.get("playerId")).intValue();
        }
        boolean validTrackId = newTrackId != null && !newTrackId.isEmpty() && !newTrackId.equals("null");
        boolean validRekordboxId = newRekordboxId != null && !newRekordboxId.isEmpty() && !newRekordboxId.equals("null");
        
        // Player 切换检测（排除 newPlayerId = 0 的误触发）
        if (newPlayerId != currentPlayerId && currentPlayerId != 0 && newPlayerId > 0) {
            // Player 切换，触发完整状态重置
            // pendingReanchor = true; // TEMP DISABLED
            pendingReanchorTargetMs = newPositionMs;
            pendingReanchorReason = "player_change";
            reanchorCount++;
            // 重置与旧 source 绑定的状态
            lastPositionMs = newPositionMs;
            currentTrackId = newTrackId;
            currentRekordboxId = newRekordboxId;
            System.out.println("[LTC] PLAYER CHANGE: from " + currentPlayerId + " to " + newPlayerId + " reanchor to " + newPositionMs + "ms");
        }
        currentPlayerId = newPlayerId;
        
        // 优先条件：有效的 trackId 或 rekordboxId 变化
        if ((validTrackId && !newTrackId.equals(currentTrackId)) || 
            (validRekordboxId && !newRekordboxId.equals(currentRekordboxId))) {
            if (validTrackId) currentTrackId = newTrackId;
            if (validRekordboxId) currentRekordboxId = newRekordboxId;
            // pendingReanchor = true; // TEMP DISABLED
            pendingReanchorTargetMs = newPositionMs;
            pendingReanchorReason = "track_change";
            reanchorCount++;
            System.out.println("[LTC] TRACK CHANGE: reanchor to " + newPositionMs + "ms");
        } else if (!validTrackId && !validRekordboxId) {
            // 兜底条件：trackId/rekordboxId 都不可用时，用位置回跳判断
            if (newPositionMs > 0 && newPositionMs < 500 && lastPositionMs > 5000) {
                // pendingReanchor = true; // TEMP DISABLED
                pendingReanchorTargetMs = newPositionMs;
                pendingReanchorReason = "near_zero_fallback";
                reanchorCount++;
                System.out.println("[LTC] NEAR ZERO FALLBACK: reanchor to " + newPositionMs + "ms");
            }
        }

        // === stop -> restart 检测 ===
        if (!wasPlaying && sourcePlaying) {
            // 从停止恢复播放，触发一次重锚
            // pendingReanchor = true; // TEMP DISABLED
            pendingReanchorTargetMs = newPositionMs;
            pendingReanchorReason = "restart";
            reanchorCount++;
            System.out.println("[LTC] RESTART: reanchor to " + newPositionMs + "ms");
        }
        wasPlaying = sourcePlaying;

        // Hot cue 检测：大幅跳变 > 2秒
        long diff = Math.abs(newPositionMs - lastPositionMs);
        if (diff > 2000 && lastPositionMs > 0) {
            // pendingReanchor = true; // TEMP DISABLED
            pendingReanchorTargetMs = newPositionMs;
            pendingReanchorReason = "hot_cue";
            reanchorCount++;
            System.out.println("[LTC] HOT CUE: jump from " + lastPositionMs + " to " + newPositionMs);
        }
        lastPositionMs = newPositionMs;

        Object t = state.get("masterTimeSec");
        if (t instanceof Number) {
            seconds = ((Number) t).doubleValue();
        }
        Object clk = state.get("__clock");
        if (clk instanceof TimecodeClock) clock = (TimecodeClock) clk;
    }

    @Override
    public Map<String, Object> status() {
        FrameRateMode mode = FrameRateMode.fromConfig(cfg.get("fps"));
        int fps = mode.nominalFps;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("seconds", seconds);
        m.put("fps", fps);
        m.put("frameRate", mode.rateFps);
        m.put("sampleRate", line != null ? (int) line.getFormat().getSampleRate() : intCfg("sampleRate", 48000));
        m.put("deviceName", strCfg("deviceName", "default"));
        m.put("activeDevice", activeDevice);
        m.put("gainDb", numCfg("gainDb", -8.0));
        m.put("frame", frameInSecond);
        m.put("signalLevel", signalLevel);
        m.put("timecode", toTimecode(seconds, fps));
        m.put("outputState", outputState);
        m.put("sourcePlaying", sourcePlaying);
        m.put("sourceActive", sourceActive);
        m.put("sourceState", sourceState);

        // Phase A 状态
        m.put("configuredTarget", configuredTarget);
        m.put("matchedDevice", matchedDevice);
        m.put("matchMode", matchMode);
        m.put("matchScore", matchScore);
        m.put("endpointType", endpointType);
        m.put("channelRole", channelRole);
        m.put("deviceOpenable", deviceOpenable);
        m.put("warning", warning);
        m.put("lastSuccessfulDevice", lastSuccessfulDevice);

        // 简化状态
        m.put("reanchorCount", reanchorCount);
        m.put("pendingReanchor", pendingReanchor);
        m.put("pendingReanchorReason", pendingReanchorReason);
        m.put("error", lastError);
        return m;
    }

    // === 固定输出速率（pitch=0）===
    private static final double FIXED_RATE = 1.0;

    private void startLtc() {
        running = true;
        new Thread(() -> {
            try {
                int sampleRate = intCfg("sampleRate", 48000);
                AudioFormat fmt = chooseAndOpenLine(sampleRate, strCfg("deviceName", null));
                pumpAudio(fmt);
            } catch (Exception e) {
                lastError = e.getMessage();
                outputState = "ERROR";
            }
        }).start();
    }

    private void pumpAudio(AudioFormat fmt) {
        final int sampleRate = (int) fmt.getSampleRate();
        final FrameRateMode mode = FrameRateMode.fromConfig(cfg.get("fps"));
        final double gainDb = numCfg("gainDb", -8.0);
        final double amp = Math.max(0.01, Math.min(0.95, Math.pow(10.0, gainDb / 20.0)));

        final int bufferSamples = sampleRate / 40;
        final byte[] out = new byte[bufferSamples * 2];
        final double bitRate = 80.0 * mode.rateFps;

        final LtcFrameBuilder frameBuilder = new LtcFrameBuilder(mode);
        final LtcBmcModulator mod = new LtcBmcModulator(sampleRate, bitRate);

        final double samplesPerFrameExact = (double) sampleRate / mode.rateFps;
        final double effectiveSamplesPerFrame = samplesPerFrameExact / FIXED_RATE;

        localLtcSamplePosition = 0L;
        localLtcFramePosition = 0L;
        nextFrameBoundarySample = (long) effectiveSamplesPerFrame;
        framePrimed = false;
        initialAligned = false;
        blockClockInit = true;

        while (running && line != null && line.isOpen()) {
            outputState = "OUTPUTTING";

            boolean isPlaying = sourcePlaying && sourceActive;

            if (!isPlaying) {
                outputState = "PAUSED";
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                continue;
            }

            // 应用一次性重锚（target=0 也是合法起点）
            if (pendingReanchor) {
                long reanchorSample = (pendingReanchorTargetMs * sampleRate) / 1000L;
                localLtcSamplePosition = reanchorSample;
                localLtcFramePosition = (long) (reanchorSample / effectiveSamplesPerFrame);
                nextFrameBoundarySample = (long) ((localLtcFramePosition + 1) * effectiveSamplesPerFrame);
                framePrimed = false;
                System.out.println("[LTC] APPLY REANCHOR: reason=" + pendingReanchorReason + " target=" + pendingReanchorTargetMs + "ms");
                // 清除一次性标志
                pendingReanchor = false;
                pendingReanchorTargetMs = 0L;
                pendingReanchorReason = "";
            }

            double blockStartSec = nextBlockStartSec;
            seconds = blockStartSec;
            double sumSq = 0.0;

            double positionPhase = 0.0;
            for (int i = 0; i < bufferSamples; i++) {
                positionPhase += FIXED_RATE;
                long currentSamplePos = localLtcSamplePosition + (long) positionPhase;

                if (!framePrimed || currentSamplePos >= nextFrameBoundarySample) {
                    while (currentSamplePos >= nextFrameBoundarySample) {
                        localLtcFramePosition++;
                        nextFrameBoundarySample = (long) ((localLtcFramePosition + 1) * effectiveSamplesPerFrame);
                    }
                    Timecode tc = mode.timecodeFromSeconds(localLtcFramePosition / mode.rateFps);
                    frameInSecond = tc.frame;
                    mod.loadFrame(frameBuilder.buildBits(tc));
                    framePrimed = true;
                }

                double sample = mod.nextSample() * amp;
                sumSq += sample * sample;
                short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (sample * 32767.0)));
                out[i * 2] = (byte) (v & 0xff);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }

            localLtcSamplePosition += (long) positionPhase;
            localLtcFramePosition = (long) (localLtcSamplePosition / effectiveSamplesPerFrame);
            nextBlockStartSec += bufferSamples / (double) sampleRate;

            double rms = Math.sqrt(sumSq / Math.max(1, bufferSamples));
            signalLevel = signalLevel * 0.9 + rms * 0.1; // smoother level
            try {
                line.write(out, 0, out.length);
            } catch (Exception e) {
                lastError = "音频设备写入失败: " + e.getMessage();
                outputState = "ERROR";
                running = false;
            }
        }

        if (running && (line == null || !line.isOpen())) {
            lastError = "音频输出设备不可用或已断开";
            outputState = "ERROR";
            running = false;
        }
    }

    private AudioFormat chooseAndOpenLine(int requestedSampleRate, String preferredName) throws LineUnavailableException {
        int[] rates = new int[] { requestedSampleRate, 48000, 44100 };
        for (int r : rates) {
            try {
                AudioFormat fmt = new AudioFormat(r, 16, 1, true, false);
                SourceDataLine l = openLineWithFormat(fmt, preferredName);
                if (l != null) {
                    line = l;
                    activeDevice = l.getFormat().toString();
                    return fmt;
                }
            } catch (Exception ignored) {}
        }
        throw new LineUnavailableException("No supported sample rate for selected device");
    }

    private SourceDataLine openLineWithFormat(AudioFormat fmt, String name) {
        try {
            // 先尝试按名称匹配设备
            if (name != null && !name.trim().isEmpty() && !"default".equalsIgnoreCase(name)) {
                Mixer.Info[] infos = AudioSystem.getMixerInfo();
                for (Mixer.Info info : infos) {
                    if (info.getName().toLowerCase().contains(name.toLowerCase())) {
                        Mixer m = AudioSystem.getMixer(info);
                        DataLine.Info di = new DataLine.Info(SourceDataLine.class, fmt);
                        if (m.isLineSupported(di)) {
                            SourceDataLine l = (SourceDataLine) m.getLine(di);
                            l.open(fmt);
                            l.start();
                            return l;
                        }
                    }
                }
            }
            // 回退到默认设备
            DataLine.Info di = new DataLine.Info(SourceDataLine.class, fmt);
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(di);
            l.open(fmt);
            l.start();
            return l;
        } catch (Exception e) {
            lastError = "设备打开失败: " + e.getMessage();
            return null;
        }
    }

    private double numCfg(String k, double def) {
        Object v = cfg.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    private int intCfg(String k, int def) {
        Object v = cfg.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private String strCfg(String k, String def) {
        Object v = cfg.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private String toTimecode(double sec, int fps) {
        int total = (int) Math.max(0, Math.floor(sec));
        int hh = total / 3600;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        int ff = Math.max(0, Math.min(Math.max(1, fps) - 1, frameInSecond));
        return String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff);
    }

    // === 内部类 ===
    private static class Timecode {
        int hh, mm, ss, frame;
    }

    private enum FrameRateMode {
        FPS_24(24.0, false),
        FPS_25(25.0, false),
        FPS_2997_DF(29.97, true),
        FPS_30(30.0, false);

        final double rateFps;
        final boolean dropFrame;
        final int nominalFps;
        final double frameDurationSec;

        FrameRateMode(double rateFps, boolean dropFrame) {
            this.rateFps = rateFps;
            this.dropFrame = dropFrame;
            this.nominalFps = (int) Math.round(rateFps);
            this.frameDurationSec = 1.0 / rateFps;
        }

        static FrameRateMode fromConfig(Object v) {
            double fps = 25.0;
            if (v instanceof Number) fps = ((Number) v).doubleValue();
            else {
                try { fps = Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
            }
            if (Math.abs(fps - 29.97) < 0.05) return FPS_2997_DF;
            if (Math.abs(fps - 30.0) < 0.1) return FPS_30;
            if (Math.abs(fps - 24.0) < 0.1) return FPS_24;
            return FPS_25;
        }

        Timecode timecodeFromSeconds(double sec) {
            Timecode t = new Timecode();
            if (dropFrame) {
                long totalFrames = (long) Math.floor(Math.max(0.0, sec) * 30000.0 / 1001.0);
                long d = totalFrames / 17982;
                long m = totalFrames % 17982;
                long dropped = 18L * d + 2L * Math.max(0, (int) ((m - 2) / 1798));
                long frameNumber = totalFrames + dropped;
                t.hh = (int) ((frameNumber / 108000) % 24);
                t.mm = (int) ((frameNumber / 1800) % 60);
                t.ss = (int) ((frameNumber / 30) % 60);
                t.frame = (int) (frameNumber % 30);
                return t;
            }
            long totalFrames = (long) Math.floor(Math.max(0.0, sec) * rateFps);
            t.hh = (int) ((totalFrames / (nominalFps * 3600L)) % 24);
            t.mm = (int) ((totalFrames / (nominalFps * 60L)) % 60);
            t.ss = (int) ((totalFrames / nominalFps) % 60);
            t.frame = (int) (totalFrames % nominalFps);
            return t;
        }
    }

    private static class LtcFrameBuilder {
        private final FrameRateMode mode;

        LtcFrameBuilder(FrameRateMode mode) { this.mode = mode; }

        boolean[] buildBits(Timecode tc) {
            boolean[] bits = new boolean[80];
            int frame = Math.max(0, tc.frame);
            int sec = Math.max(0, tc.ss);
            int min = Math.max(0, tc.mm);
            int hour = Math.max(0, tc.hh);

            writeBcdUnits(bits, 0, frame % 10);
            writeBcdTens(bits, 8, frame / 10, 2);
            bits[10] = false;
            bits[11] = mode.dropFrame;

            writeBcdUnits(bits, 16, sec % 10);
            writeBcdTens(bits, 24, sec / 10, 3);

            writeBcdUnits(bits, 32, min % 10);
            writeBcdTens(bits, 40, min / 10, 3);

            writeBcdUnits(bits, 48, hour % 10);
            writeBcdTens(bits, 56, hour / 10, 2);

            final int[] syncWireOrder = new int[] {0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,1};
            for (int i = 0; i < 16; i++) bits[64 + i] = syncWireOrder[i] == 1;
            return bits;
        }

        private void writeBcdUnits(boolean[] bits, int start, int v) {
            for (int i = 0; i < 4; i++) bits[start + i] = ((v >> i) & 1) == 1;
        }

        private void writeBcdTens(boolean[] bits, int start, int v, int width) {
            for (int i = 0; i < width; i++) bits[start + i] = ((v >> i) & 1) == 1;
        }
    }

    private static class LtcBmcModulator {
        private final double samplesPerBit;
        private final double halfSamples;
        private boolean[] bits = new boolean[80];
        private int bitIndex = 0;
        private double sampleInBit = 0.0;
        private double level = -1.0;
        private boolean empty = true;

        LtcBmcModulator(int sampleRate, double bitRate) {
            this.samplesPerBit = sampleRate / bitRate;
            this.halfSamples = this.samplesPerBit * 0.5;
        }

        boolean isFrameEmpty() { return empty; }

        void loadFrame(boolean[] b) {
            if (b == null || b.length != 80) return;
            this.bits = b;
            this.bitIndex = 0;
            this.sampleInBit = 0.0;
            this.empty = false;
        }

        double nextSample() {
            if (empty) return 0.0;

            if (sampleInBit == 0.0) {
                level = -level;
            }

            if (bits[bitIndex]) {
                if (sampleInBit >= halfSamples && (sampleInBit - 1.0) < halfSamples) {
                    level = -level;
                }
            }

            double out = level;

            sampleInBit += 1.0;
            if (sampleInBit >= samplesPerBit) {
                sampleInBit -= samplesPerBit;
                bitIndex++;
                if (bitIndex >= 80) bitIndex = 0;
                if (sampleInBit < 1e-9) sampleInBit = 0.0;
            }
            return out;
        }
    }
}
