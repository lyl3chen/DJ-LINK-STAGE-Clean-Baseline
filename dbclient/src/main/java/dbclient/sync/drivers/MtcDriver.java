package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeClock;

import javax.sound.midi.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class MtcDriver implements OutputDriver {
    // 这是 MTC 实装驱动：把统一时钟转成 MIDI Quarter Frame 发给选定的 MIDI Out 端口。
    private volatile boolean running;
    private volatile int qfCounter;
    private volatile String outputState = "IDLE";
    private volatile boolean debugLog = false;
    private volatile String lastError = "";
    private volatile int lastDataByte = -1;
    private volatile long lastSendTsMs = 0L;
    private volatile double lastJitterMs = 0.0;
    private volatile long lateCycles = 0L;
    private volatile long roundId = 0L;
    private volatile double recentMaxJitterMs = 0.0;
    private volatile double recentAvgJitterMs = 0.0;
    private long jitterSamples = 0L;
    private double jitterSumMs = 0.0;
    private volatile String activePort = "-";
    private volatile long pulseAtMs = 0L;
    private volatile boolean sourcePlaying = false;
    private volatile boolean sourceActive = false;
    private volatile String sourceState = "OFFLINE";
    private volatile long lastSourceUpdateMs = 0L;
    private volatile double seconds = 0.0;
    private volatile TimecodeClock clock;
    private Map<String, Object> cfg = new LinkedHashMap<>();

    private MidiDevice device;
    private Receiver receiver;
    private Thread sendThread;

    // 当前一轮8个QF锁定的时间帧（避免跨帧混包）
    private volatile int lockedHh = 0;
    private volatile int lockedMm = 0;
    private volatile int lockedSs = 0;
    private volatile int lockedFf = 0;

    public String name() { return "mtc"; }

    public synchronized void start(Map<String, Object> config) {
        cfg = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
        String wanted = String.valueOf(cfg.getOrDefault("midiPort", ""));
        debugLog = Boolean.TRUE.equals(cfg.get("debug"));
        try {
            device = openMidiOut(wanted);
            receiver = device.getReceiver();
            activePort = device.getDeviceInfo().getName();
            running = true;
            outputState = "RUNNING";
            lastError = "";
            sendThread = new Thread(this::sendLoop, "mtc-send-thread");
            sendThread.setDaemon(true);
            sendThread.setPriority(Thread.MAX_PRIORITY);
            sendThread.start();
        } catch (Exception e) {
            running = false;
            outputState = "ERROR";
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            activePort = "-";
        }
    }

    public synchronized void stop() {
        running = false;
        outputState = "STOPPED";
        if (sendThread != null) {
            try { sendThread.join(300); } catch (InterruptedException ignored) {}
            sendThread = null;
        }
        if (receiver != null) {
            try { receiver.close(); } catch (Exception ignored) {}
            receiver = null;
        }
        if (device != null) {
            try { device.close(); } catch (Exception ignored) {}
            device = null;
        }
    }

    public void update(Map<String, Object> state) {
        if (!running || state == null) return;
        sourcePlaying = Boolean.TRUE.equals(state.get("sourcePlaying"));
        sourceActive = Boolean.TRUE.equals(state.get("sourceActive"));
        sourceState = String.valueOf(state.getOrDefault("sourceState", "OFFLINE"));
        lastSourceUpdateMs = System.currentTimeMillis();
        Object t = state.get("masterTimeSec");
        if (t instanceof Number) seconds = ((Number) t).doubleValue();
        Object clk = state.get("__clock");
        if (clk instanceof TimecodeClock) clock = (TimecodeClock) clk;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("quarterFrame", qfCounter);
        m.put("qfCounter", qfCounter);
        m.put("lockedHh", lockedHh);
        m.put("lockedMm", lockedMm);
        m.put("lockedSs", lockedSs);
        m.put("lockedFf", lockedFf);
        m.put("lastDataByte", lastDataByte);
        m.put("lastSendTsMs", lastSendTsMs);
        m.put("lastJitterMs", lastJitterMs);
        m.put("lateCycles", lateCycles);
        m.put("roundId", roundId);
        m.put("recentMaxJitterMs", recentMaxJitterMs);
        m.put("recentAvgJitterMs", recentAvgJitterMs);
        m.put("midiPort", cfg.getOrDefault("midiPort", ""));
        m.put("activePort", activePort);
        m.put("outputState", outputState);
        m.put("error", lastError);
        m.put("pulseAtMs", pulseAtMs);
        m.put("sourcePlaying", sourcePlaying);
        m.put("sourceActive", sourceActive);
        m.put("sourceState", sourceState);
        m.put("debug", debugLog);
        return m;
    }

    private void sendLoop() {
        int fps = 25;
        long intervalNs = (long) ((1_000_000_000.0 / fps) / 8.0); // 1000/FPS/8
        long next = System.nanoTime();
        while (running) {
            long nowNs = System.nanoTime();
            if (nowNs < next) {
                try {
                    long sleepMs = Math.max(0, (next - nowNs) / 1_000_000L);
                    if (sleepMs > 0) Thread.sleep(sleepMs);
                    else Thread.yield();
                } catch (InterruptedException ignored) {}
                continue;
            }

            long jitterNs = nowNs - next;
            long late = intervalNs > 0 ? Math.max(0, jitterNs / intervalNs) : 0;
            next += intervalNs;
            if (late > 0) next += late * intervalNs; // 避免追赶时拥挤发送

            lastJitterMs = jitterNs / 1_000_000.0;
            lateCycles = late;
            double absJ = Math.abs(lastJitterMs);
            recentMaxJitterMs = Math.max(recentMaxJitterMs * 0.98, absJ);
            jitterSamples++;
            jitterSumMs += absJ;
            recentAvgJitterMs = jitterSumMs / Math.max(1L, jitterSamples);

            if (Math.abs(jitterNs) > 2_000_000L) {
                System.out.println("[MTC] jitter(ms)=" + lastJitterMs + ", lateCycles=" + late);
            }

            boolean sourceFresh = (System.currentTimeMillis() - lastSourceUpdateMs) <= 500;
            if (!sourceFresh || !sourceActive || !sourcePlaying) {
                outputState = sourceFresh ? (sourceActive ? "SILENT_SOURCE_STOP" : "SILENT_SOURCE_OFFLINE") : "SILENT_SOURCE_TIMEOUT";
                continue;
            }

            double sec = clock != null ? clock.nowSeconds() : seconds;
            try {
                if (qfCounter == 0) {
                    lockCurrentFrame(sec);
                    roundId++;
                }
                int qfBefore = qfCounter;
                int data = sendQuarterFrameLocked();
                pulseAtMs = System.currentTimeMillis();
                lastSendTsMs = pulseAtMs;
                lastDataByte = data & 0xFF;
                outputState = "OUTPUTTING";
                if (debugLog) {
                    System.out.println(String.format(
                            "[MTC][QF] t=%d qf=%d tc=%02d:%02d:%02d:%02d data=0x%02X jitterMs=%.3f lateCycles=%d",
                            System.currentTimeMillis(), qfBefore, lockedHh, lockedMm, lockedSs, lockedFf,
                            data & 0xFF, lastJitterMs, lateCycles));
                }
            } catch (Exception e) {
                outputState = "ERROR";
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                running = false;
            }
        }
    }

    private void lockCurrentFrame(double sec) {
        int fps = 25;
        int total = (int) Math.max(0, Math.floor(sec));
        lockedHh = (total / 3600) & 0x1F;
        lockedMm = (total % 3600) / 60;
        lockedSs = total % 60;
        lockedFf = (int) Math.floor((sec - Math.floor(sec)) * fps);
    }

    private int sendQuarterFrameLocked() throws InvalidMidiDataException {
        int nibble;
        switch (qfCounter) {
            case 0: nibble = lockedFf & 0x0F; break;
            case 1: nibble = (lockedFf >> 4) & 0x01; break;
            case 2: nibble = lockedSs & 0x0F; break;
            case 3: nibble = (lockedSs >> 4) & 0x03; break;
            case 4: nibble = lockedMm & 0x0F; break;
            case 5: nibble = (lockedMm >> 4) & 0x03; break;
            case 6: nibble = lockedHh & 0x0F; break;
            default:
                int rateFlag = 0x01; // 25fps
                nibble = ((lockedHh >> 4) & 0x01) | (rateFlag << 1);
                break;
        }

        int data = ((qfCounter & 0x07) << 4) | (nibble & 0x0F);
        ShortMessage sm = new ShortMessage();
        sm.setMessage(0xF1, data, 0);
        receiver.send(sm, -1);
        qfCounter = (qfCounter + 1) % 8;
        return data;
    }

    private MidiDevice openMidiOut(String wanted) throws MidiUnavailableException {
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        for (MidiDevice.Info info : infos) {
            MidiDevice d = MidiSystem.getMidiDevice(info);
            boolean isOutput = d.getMaxReceivers() != 0;
            if (!isOutput) continue;
            String name = info.getName();
            if (wanted != null && !wanted.isBlank() && !"default".equalsIgnoreCase(wanted)) {
                if (!name.toLowerCase().contains(wanted.toLowerCase())) continue;
            }
            d.open();
            return d;
        }
        // fallback: first output device
        for (MidiDevice.Info info : infos) {
            MidiDevice d = MidiSystem.getMidiDevice(info);
            if (d.getMaxReceivers() != 0) {
                d.open();
                return d;
            }
        }
        throw new MidiUnavailableException("No MIDI output device found");
    }
}
