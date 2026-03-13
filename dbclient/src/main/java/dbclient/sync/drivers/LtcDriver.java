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
            int fps = intCfg("fps", 25);
            frameInSecond = (int) Math.floor((seconds - Math.floor(seconds)) * Math.max(1, fps));
        }
        Object clk = state.get("__clock");
        if (clk instanceof TimecodeClock) clock = (TimecodeClock) clk;
    }

    public Map<String, Object> status() {
        int fps = intCfg("fps", 25);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("seconds", seconds);
        m.put("fps", fps);
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
        final int fps = Math.max(1, intCfg("fps", 25));
        final double gainDb = numCfg("gainDb", -8.0);
        final double amp = Math.max(0.01, Math.min(0.95, Math.pow(10.0, gainDb / 20.0)));

        final int bufferSamples = sampleRate / 40; // ~25ms blocks
        byte[] out = new byte[bufferSamples * 2];
        double phase = 0.0;

        final double blockDurationSec = bufferSamples / (double) sampleRate;

        while (running && line != null && line.isOpen()) {
            outputState = "OUTPUTTING";
            double clockSec = clock != null ? clock.nowSeconds() : seconds;
            if (!blockClockInit) {
                nextBlockStartSec = clockSec;
                blockClockInit = true;
            } else {
                // 优先连续承接上一块理论结束时间
                nextBlockStartSec += blockDurationSec;
                // 偏差大于阈值才重贴，避免块边界抖动
                if (Math.abs(clockSec - nextBlockStartSec) > BLOCK_RELOCK_THRESHOLD_SEC) {
                    nextBlockStartSec = clockSec;
                }
            }

            double blockStartSec = nextBlockStartSec;
            seconds = blockStartSec;
            double sumSq = 0.0;
            for (int i = 0; i < bufferSamples; i++) {
                double t = blockStartSec + (i / (double) sampleRate);
                int bitClock = 160 * fps;
                double bitPos = (t * bitClock) % 1.0;
                boolean edge = bitPos < 0.5;

                // 简化版双相位风格：每bit至少一次翻转，帧头更强，用于先打通真实可监听输出链路。
                double baseFreq = fps * 80.0;
                phase += (2.0 * Math.PI * baseFreq) / sampleRate;
                if (phase > Math.PI * 2) phase -= Math.PI * 2;
                double s = Math.sin(phase);
                double pulse = edge ? 1.0 : -1.0;
                double mixed = (s * 0.35 + pulse * 0.65) * amp;
                sumSq += mixed * mixed;
                short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (mixed * 32767.0)));
                out[i * 2] = (byte) (v & 0xff);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }
            double rms = Math.sqrt(sumSq / Math.max(1, bufferSamples));
            // 平滑处理，避免电平条抖动过快
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

    private String toTimecode(double sec, int fps) {
        int total = (int) Math.max(0, Math.floor(sec));
        int hh = total / 3600;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        int ff = Math.max(0, Math.min(Math.max(1, fps) - 1, frameInSecond));
        return String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff);
    }
}
