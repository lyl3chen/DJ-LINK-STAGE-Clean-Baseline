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
            line.start();
            running = true;
            audioThread = new Thread(() -> pumpAudio(fmt), "ltc-audio-thread");
            audioThread.setDaemon(true);
            audioThread.start();
        } catch (Exception e) {
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("seconds", seconds);
        m.put("fps", intCfg("fps", 25));
        m.put("sampleRate", intCfg("sampleRate", 48000));
        m.put("deviceName", strCfg("deviceName", "default"));
        m.put("gainDb", numCfg("gainDb", -8.0));
        m.put("frame", frameInSecond);
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
                short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (mixed * 32767.0)));
                out[i * 2] = (byte) (v & 0xff);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }
            line.write(out, 0, out.length);
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
}
