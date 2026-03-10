package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;

import javax.sound.midi.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class MtcDriver implements OutputDriver {
    // 这是 MTC 实装驱动：把统一时钟转成 MIDI Quarter Frame 发给选定的 MIDI Out 端口。
    private volatile boolean running;
    private volatile int qfCounter;
    private volatile String outputState = "IDLE";
    private volatile String lastError = "";
    private volatile String activePort = "-";
    private volatile long pulseAtMs = 0L;
    private volatile boolean sourcePlaying = false;
    private volatile boolean sourceActive = false;
    private volatile long lastSourceUpdateMs = 0L;
    private volatile double seconds = 0.0;
    private volatile double anchorSeconds = 0.0;
    private volatile long anchorAtMs = 0L;
    private Map<String, Object> cfg = new LinkedHashMap<>();

    private MidiDevice device;
    private Receiver receiver;
    private long lastQuarterFrameSentAt = 0L;

    public String name() { return "mtc"; }

    public synchronized void start(Map<String, Object> config) {
        cfg = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
        String wanted = String.valueOf(cfg.getOrDefault("midiPort", ""));
        try {
            device = openMidiOut(wanted);
            receiver = device.getReceiver();
            activePort = device.getDeviceInfo().getName();
            running = true;
            outputState = "RUNNING";
            lastError = "";
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
        lastSourceUpdateMs = System.currentTimeMillis();
        Object t = state.get("masterTimeSec");
        if (t instanceof Number) {
            seconds = ((Number) t).doubleValue();
            anchorSeconds = seconds;
            anchorAtMs = System.currentTimeMillis();
        }

        boolean sourceFresh = (System.currentTimeMillis() - lastSourceUpdateMs) <= 500;
        boolean shouldOutput = sourceFresh && sourcePlaying && sourceActive;
        if (!shouldOutput) {
            outputState = sourceFresh ? (sourceActive ? "SILENT_SOURCE_STOP" : "SILENT_SOURCE_OFFLINE") : "SILENT_SOURCE_TIMEOUT";
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastQuarterFrameSentAt < 20) return; // ~50Hz QF burst
        lastQuarterFrameSentAt = now;

        try {
            sendQuarterFrame(chasedSeconds());
            pulseAtMs = now;
            outputState = "OUTPUTTING";
        } catch (Exception e) {
            outputState = "ERROR";
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            running = false;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("quarterFrame", qfCounter);
        m.put("midiPort", cfg.getOrDefault("midiPort", ""));
        m.put("activePort", activePort);
        m.put("outputState", outputState);
        m.put("error", lastError);
        m.put("pulseAtMs", pulseAtMs);
        m.put("sourcePlaying", sourcePlaying);
        m.put("sourceActive", sourceActive);
        return m;
    }

    private double chasedSeconds() {
        if (!sourcePlaying) return seconds;
        if (anchorAtMs <= 0) return seconds;
        return Math.max(0.0, anchorSeconds + Math.max(0, (System.currentTimeMillis() - anchorAtMs)) / 1000.0);
    }

    private void sendQuarterFrame(double sec) throws InvalidMidiDataException {
        int fps = 25;
        int total = (int) Math.max(0, Math.floor(sec));
        int hh = (total / 3600) & 0x1F;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        int ff = (int) Math.floor((sec - Math.floor(sec)) * fps);

        int nibble;
        switch (qfCounter) {
            case 0: nibble = ff & 0x0F; break;
            case 1: nibble = (ff >> 4) & 0x01; break;
            case 2: nibble = ss & 0x0F; break;
            case 3: nibble = (ss >> 4) & 0x03; break;
            case 4: nibble = mm & 0x0F; break;
            case 5: nibble = (mm >> 4) & 0x03; break;
            case 6: nibble = hh & 0x0F; break;
            default:
                int rateFlag = 0x01; // 25fps
                nibble = ((hh >> 4) & 0x01) | (rateFlag << 1);
                break;
        }

        int data = ((qfCounter & 0x07) << 4) | (nibble & 0x0F);
        ShortMessage sm = new ShortMessage();
        sm.setMessage(0xF1, data, 0);
        receiver.send(sm, -1);
        qfCounter = (qfCounter + 1) % 8;
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
