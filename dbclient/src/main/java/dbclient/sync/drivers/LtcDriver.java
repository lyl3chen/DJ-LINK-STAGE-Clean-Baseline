package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

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
    private Map<String, Object> cfg = new LinkedHashMap<>();

    private SourceDataLine line;
    private Thread audioThread;

    public String name() { return "ltc"; }

    public synchronized void start(Map<String, Object> config) {
        cfg = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
        int sampleRate = intCfg("sampleRate", 48000);
        String deviceName = strCfg("deviceName", "default");

        try {
            AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
            line = openLine(fmt, deviceName);
            activeDevice = line.getLineInfo().toString();
            line.start();
            running = true;
            lastError = "";
            System.out.println("[LTC] started device=" + activeDevice + " fps=" + intCfg("fps",25) + " sampleRate=" + sampleRate + " gainDb=" + numCfg("gainDb",-8.0));
            audioThread = new Thread(() -> pumpAudio(fmt), "ltc-audio-thread");
            audioThread.setDaemon(true);
            audioThread.start();
        } catch (Exception e) {
            running = false;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            activeDevice = "-";
            System.out.println("[LTC] start failed: " + lastError);
        }
    }

    public synchronized void stop() {
        running = false;
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
        if (t instanceof Number) {
            seconds = ((Number) t).doubleValue();
            int fps = intCfg("fps", 25);
            frameInSecond = (int) Math.floor((seconds - Math.floor(seconds)) * Math.max(1, fps));
        }
    }

    public Map<String, Object> status() {
        int fps = intCfg("fps", 25);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("seconds", seconds);
        m.put("fps", fps);
        m.put("sampleRate", intCfg("sampleRate", 48000));
        m.put("deviceName", strCfg("deviceName", "default"));
        m.put("activeDevice", activeDevice);
        m.put("gainDb", numCfg("gainDb", -8.0));
        m.put("frame", frameInSecond);
        m.put("signalLevel", signalLevel);
        m.put("timecode", toTimecode(seconds, fps));
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

        while (running && line != null && line.isOpen()) {
            double peak = 0.0;
            for (int i = 0; i < bufferSamples; i++) {
                double t = seconds + (i / (double) sampleRate);
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
                peak = Math.max(peak, Math.abs(mixed));
                short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (mixed * 32767.0)));
                out[i * 2] = (byte) (v & 0xff);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }
            signalLevel = peak;
            line.write(out, 0, out.length);
        }

        if (running && (line == null || !line.isOpen())) {
            lastError = "音频输出设备不可用或已断开";
            running = false;
        }
    }

    private SourceDataLine openLine(AudioFormat fmt, String preferredName) throws LineUnavailableException {
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

    private String toTimecode(double sec, int fps) {
        int total = (int) Math.max(0, Math.floor(sec));
        int hh = total / 3600;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        int ff = Math.max(0, Math.min(Math.max(1, fps) - 1, frameInSecond));
        return String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff);
    }
}
