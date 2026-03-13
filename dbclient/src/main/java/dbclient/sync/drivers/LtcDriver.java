package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeClock;

import javax.sound.sampled.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class LtcDriver implements OutputDriver {
    // 这一段是真实 LTC 音频输出驱动：根据主时钟持续生成可被外部设备识别的时间码音频信号。
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
    // LTC block 连续承接时间基准
    private volatile boolean blockClockInit = false;
    private volatile double nextBlockStartSec = 0.0;
    private static final double BLOCK_RELOCK_THRESHOLD_SEC = 0.020; // 20ms
    private Map<String, Object> cfg = new LinkedHashMap<>();

    private SourceDataLine line;
    private Thread audioThread;

    public String name() { return "ltc"; }

    public synchronized void start(Map<String, Object> config) {
        cfg = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
        int sampleRate = intCfg("sampleRate", 48000);
        String deviceName = strCfg("deviceName", "default");
        configuredTarget = deviceName;

        try {
            AudioFormat fmt = chooseAndOpenLine(sampleRate, deviceName);
            activeDevice = describeActiveDevice(line, deviceName);
            matchedDevice = activeDevice;
            line.start();
            running = true;
            outputState = "RUNNING";
            blockClockInit = false;
            nextBlockStartSec = 0.0;
            lastError = "";
            endpointType = classifyEndpoint(activeDevice);
            channelRole = classifyChannelRole(activeDevice);
            deviceOpenable = true;
            matchMode = matchModeOf(configuredTarget, activeDevice);
            matchScore = computeMatchScore(configuredTarget, activeDevice, true);
            warning = buildWarning(endpointType, configuredTarget, activeDevice, "");
            lastSuccessfulDevice = activeDevice;
            System.out.println("[LTC] started device=" + activeDevice + " fps=" + intCfg("fps",25) + " sampleRate=" + (int)fmt.getSampleRate() + " gainDb=" + numCfg("gainDb",-8.0));
            audioThread = new Thread(() -> pumpAudio(fmt), "ltc-audio-thread");
            audioThread.setDaemon(true);
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
        } catch (Exception e) {
            running = false;
            outputState = "ERROR";
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            String occupancy = detectOccupancyHint(strCfg("deviceName", "default"));
            lastError = occupancy.isBlank() ? msg : (msg + " | " + occupancy);
            activeDevice = "-";
            matchedDevice = "-";
            endpointType = classifyEndpoint(configuredTarget);
            channelRole = classifyChannelRole(configuredTarget);
            deviceOpenable = false;
            matchMode = "fallback";
            matchScore = computeMatchScore(configuredTarget, "-", false);
            warning = buildWarning(endpointType, configuredTarget, "-", lastError);
            System.out.println("[LTC] start failed: " + lastError);
        }
    }

    public synchronized void stop() {
        running = false;
        outputState = "STOPPED";
        blockClockInit = false;
        signalLevel = 0.0;
        if (audioThread != null) {
            try { audioThread.join(300); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {}
            line = null;
        }
    }

    public void update(Map<String, Object> state) {
        if (!running || state == null) return;
        Object t = state.get("masterTimeSec");
        Object sp = state.get("sourcePlaying");
        Object sa = state.get("sourceActive");
        sourcePlaying = Boolean.TRUE.equals(sp);
        sourceActive = Boolean.TRUE.equals(sa);
        sourceState = String.valueOf(state.getOrDefault("sourceState", "OFFLINE"));
        lastSourceUpdateMs = System.currentTimeMillis();

        if (t instanceof Number) {
            seconds = ((Number) t).doubleValue();
        }
        Object clk = state.get("__clock");
        if (clk instanceof TimecodeClock) clock = (TimecodeClock) clk;
    }

    public Map<String, Object> status() {
        FrameRateMode mode = FrameRateMode.fromConfig(cfg.get("fps"));
        int fps = mode.nominalFps;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("seconds", seconds);
        m.put("fps", fps);
        m.put("frameRate", mode.rateFps);
        m.put("dropFrame", mode.dropFrame);
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

        // Phase A 解释性状态
        m.put("configuredTarget", configuredTarget);
        m.put("matchedDevice", matchedDevice);
        m.put("matchMode", matchMode);
        m.put("matchScore", matchScore);
        m.put("endpointType", endpointType);
        m.put("channelRole", channelRole);
        m.put("deviceOpenable", deviceOpenable);
        m.put("warning", warning);
        m.put("lastSuccessfulDevice", lastSuccessfulDevice);

        m.put("blockRelockThresholdSec", BLOCK_RELOCK_THRESHOLD_SEC);
        m.put("error", lastError);
        return m;
    }

    private void pumpAudio(AudioFormat fmt) {
        final int sampleRate = (int) fmt.getSampleRate();
        final FrameRateMode mode = FrameRateMode.fromConfig(cfg.get("fps"));
        final double gainDb = numCfg("gainDb", -8.0);
        final double amp = Math.max(0.01, Math.min(0.95, Math.pow(10.0, gainDb / 20.0)));

        final int bufferSamples = sampleRate / 40; // ~25ms
        final byte[] out = new byte[bufferSamples * 2];
        final double bitRate = 80.0 * mode.rateFps;

        final LtcFrameBuilder frameBuilder = new LtcFrameBuilder(mode);
        final LtcBmcModulator mod = new LtcBmcModulator(sampleRate, bitRate);

        final double blockDurationSec = bufferSamples / (double) sampleRate;
        final double relockThresholdSec = mode.frameDurationSec; // 偏差超过1帧才重锁

        double outputTimelineSec = 0.0;
        double nextFrameAtSec = 0.0;
        boolean framePrimed = false;

        while (running && line != null && line.isOpen()) {
            outputState = "OUTPUTTING";
            double clockSec = clock != null ? clock.nowSeconds() : seconds;
            if (!blockClockInit) {
                nextBlockStartSec = clockSec;
                outputTimelineSec = clockSec;
                nextFrameAtSec = clockSec;
                framePrimed = false;
                blockClockInit = true;
            } else {
                // 严格 frame-locked/stream-locked 推进，不做每轮细粒度 wall-clock 重贴。
                nextBlockStartSec += blockDurationSec;
                outputTimelineSec += blockDurationSec;

                // 仅偏差明显时重锁（> 1 frame）
                if (Math.abs(clockSec - outputTimelineSec) > relockThresholdSec) {
                    nextBlockStartSec = clockSec;
                    outputTimelineSec = clockSec;
                    nextFrameAtSec = clockSec;
                    framePrimed = false;
                }
            }

            double blockStartSec = nextBlockStartSec;
            seconds = blockStartSec;
            double sumSq = 0.0;
            for (int i = 0; i < bufferSamples; i++) {
                double t = blockStartSec + (i / (double) sampleRate);

                // 每帧只构建一次完整 80-bit，帧内保持不变。
                if (!framePrimed) {
                    Timecode tc = mode.timecodeFromSeconds(nextFrameAtSec);
                    frameInSecond = tc.frame;
                    mod.loadFrame(frameBuilder.buildBits(tc));
                    framePrimed = true;
                    nextFrameAtSec += mode.frameDurationSec;
                }
                while (t >= nextFrameAtSec) {
                    Timecode tc = mode.timecodeFromSeconds(nextFrameAtSec);
                    frameInSecond = tc.frame;
                    mod.loadFrame(frameBuilder.buildBits(tc));
                    nextFrameAtSec += mode.frameDurationSec;
                }

                double sample = mod.nextSample() * amp;
                sumSq += sample * sample;
                short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (sample * 32767.0)));
                out[i * 2] = (byte) (v & 0xff);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }

            double rms = Math.sqrt(sumSq / Math.max(1, bufferSamples));
            signalLevel = signalLevel * 0.55 + rms * 0.45;
            try {
                line.write(out, 0, out.length);
            } catch (Exception e) {
                lastError = "音频设备写入失败: " + (e.getMessage() == null ? e.toString() : e.getMessage());
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
                    return fmt;
                }
            } catch (Exception ignored) {}
        }
        throw new LineUnavailableException("No supported sample rate for selected device");
    }

    private SourceDataLine openLineWithFormat(AudioFormat fmt, String preferredName) throws LineUnavailableException {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        if (preferredName != null && !preferredName.trim().isEmpty() && !"default".equalsIgnoreCase(preferredName)) {
            for (Mixer.Info info : infos) {
                if (info.getName().toLowerCase().contains(preferredName.toLowerCase())) {
                    Mixer m = AudioSystem.getMixer(info);
                    DataLine.Info di = new DataLine.Info(SourceDataLine.class, fmt);
                    if (m.isLineSupported(di)) {
                        SourceDataLine l = (SourceDataLine) m.getLine(di);
                        l.open(fmt);
                        return l;
                    }
                }
            }
        }
        DataLine.Info di = new DataLine.Info(SourceDataLine.class, fmt);
        SourceDataLine l = (SourceDataLine) AudioSystem.getLine(di);
        l.open(fmt);
        return l;
    }

    private int intCfg(String key, int def) {
        Object v = cfg.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    private double numCfg(String key, double def) {
        Object v = cfg.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    private String strCfg(String key, String def) {
        Object v = cfg.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private String describeActiveDevice(SourceDataLine l, String configured) {
        String lineInfo = l == null ? "-" : String.valueOf(l.getLineInfo());
        String cfgDev = configured == null ? "default" : configured;
        return cfgDev + " | " + lineInfo;
    }

    private String detectOccupancyHint(String configured) {
        try {
            String dev = configured == null ? "" : configured.trim().toLowerCase();
            if (!dev.matches("hw:\\d+,\\d+")) return "";
            String[] p = dev.substring(3).split(",");
            int card = Integer.parseInt(p[0]);
            int pcm = Integer.parseInt(p[1]);
            String target = "/dev/snd/pcmC" + card + "D" + pcm + "p";
            Process proc = new ProcessBuilder("bash", "-lc", "fuser -v " + target + " 2>/dev/null || true").start();
            String out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            if (out == null || out.isBlank()) return "";
            String s = out.replaceAll("\\s+", " ").trim();
            if (s.length() > 160) s = s.substring(0, 160) + "...";
            return "设备可能被占用(" + target + "): " + s;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String classifyEndpoint(String text) {
        String s = text == null ? "" : text.toLowerCase();
        if (s.contains("hdmi") || s.contains("display audio")) return "hdmi";
        if (s.contains("spdif") || s.contains("iec958") || s.contains("digital")) return "digital_spdif";
        if (s.contains("usb")) {
            if (s.contains("analog") || s.contains("headphone") || s.contains("line out") || s.contains("speaker")) return "usb_audio_analog_like";
            return "usb_audio_digital_like";
        }
        if (s.contains("analog") || s.contains("headphone") || s.contains("front") || s.contains("line out") || s.contains("speaker")) return "analog";
        return "unknown";
    }

    private String classifyChannelRole(String text) {
        String s = text == null ? "" : text.toLowerCase();
        if (s.contains("headphone")) return "headphone";
        if (s.contains("front")) return "front";
        if (s.contains("line out")) return "lineout";
        if (s.contains("speaker")) return "speaker";
        if (s.contains("spdif") || s.contains("digital") || s.contains("hdmi")) return "digital";
        return "unknown";
    }

    private String matchModeOf(String configured, String matched) {
        String c = configured == null ? "" : configured.trim().toLowerCase();
        String m = matched == null ? "" : matched.trim().toLowerCase();
        if (c.isBlank() || "default".equals(c)) return "fallback";
        if (m.contains(c) || c.contains(m)) return "exact";
        return "fuzzy";
    }

    private int computeMatchScore(String configured, String matched, boolean openable) {
        int score = 0;
        if (openable) score += 40;
        String mode = matchModeOf(configured, matched);
        if ("exact".equals(mode)) score += 40;
        else if ("fuzzy".equals(mode)) score += 25;
        String ep = classifyEndpoint(matched);
        if ("analog".equals(ep) || "usb_audio_analog_like".equals(ep)) score += 20;
        if ("hdmi".equals(ep) || "digital_spdif".equals(ep) || "usb_audio_digital_like".equals(ep)) score -= 10;
        return Math.max(0, Math.min(100, score));
    }

    private String buildWarning(String endpoint, String configured, String matched, String err) {
        if (err != null && !err.isBlank()) return err;
        if ("hdmi".equals(endpoint) || "digital_spdif".equals(endpoint) || "usb_audio_digital_like".equals(endpoint)) {
            return "当前命中数字输出口，LTC建议使用模拟输出（Analog/Headphone/Front/Line Out）";
        }
        if ("fallback".equals(matchModeOf(configured, matched))) {
            return "当前为回退命中，建议确认目标设备是否符合预期";
        }
        return "";
    }

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

            // Frames
            writeBcdUnits(bits, 0, frame % 10);
            writeBcdTens(bits, 8, frame / 10, 2);
            bits[10] = false;                 // color frame
            bits[11] = mode.dropFrame;        // drop-frame flag

            // Seconds
            writeBcdUnits(bits, 16, sec % 10);
            writeBcdTens(bits, 24, sec / 10, 3);

            // Minutes
            writeBcdUnits(bits, 32, min % 10);
            writeBcdTens(bits, 40, min / 10, 3);

            // Hours
            writeBcdUnits(bits, 48, hour % 10);
            writeBcdTens(bits, 56, hour / 10, 2);

            // Sync word 16 bits（按线序写入，避免位序方向歧义）
            // 线序目标：0011111111111101
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
            // 关键：不在 load 时额外翻转，避免与 bit-start 翻转重复导致时序异常。
        }

        double nextSample() {
            if (empty) return 0.0;

            // BMC 规则1：每个 bit 起始处必翻转
            if (sampleInBit == 0.0) {
                level = -level;
            }

            // BMC 规则2：bit=1 时在半 bit 处再翻转一次
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

    private String toTimecode(double sec, int fps) {
        int total = (int) Math.max(0, Math.floor(sec));
        int hh = total / 3600;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        int ff = Math.max(0, Math.min(Math.max(1, fps) - 1, frameInSecond));
        return String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff);
    }
}
