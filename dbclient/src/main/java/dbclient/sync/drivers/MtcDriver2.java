package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeTimeline;
import javax.sound.midi.*;
import java.util.Map;

/**
 * MtcDriver2 - 基于 TimecodeTimeline 的新 MTC 驱动
 */
public class MtcDriver2 implements OutputDriver {
    
    public String name() { return "mtc2"; }
    
    private volatile boolean running = false;
    private Thread sendThread = null;
    private Map<String, Object> cfg = new java.util.LinkedHashMap<>();
    private final TimecodeTimeline timeline = TimecodeTimeline.getInstance();
    
    private MidiDevice device = null;
    private Receiver receiver = null;
    private String midiPort = "default";
    
    private volatile String outputState = "STOPPED";
    private volatile String lastError = null;
    private int qfCounter = 0;
    
    @Override
    public void start(Map<String, Object> config) {
        if (running) return;
        
        cfg = config != null ? new java.util.LinkedHashMap<>(config) : new java.util.LinkedHashMap<>();
        
        try {
            midiPort = strCfg("midiPort", "default");
            
            MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
            for (MidiDevice.Info info : infos) {
                if (info.getName().contains(midiPort) || info.getName().equals(midiPort)) {
                    try {
                        device = MidiSystem.getMidiDevice(info);
                        device.open();
                        receiver = device.getReceiver();
                        break;
                    } catch (Exception ignored) {}
                }
            }
            
            if (receiver == null && infos.length > 0) {
                device = MidiSystem.getMidiDevice(infos[0]);
                device.open();
                receiver = device.getReceiver();
            }
            
            running = true;
            outputState = "RUNNING";
            
            sendThread = new Thread(this::sendLoop, "mtc2-send");
            sendThread.setDaemon(true);
            sendThread.setPriority(Thread.MAX_PRIORITY);
            sendThread.start();
            
            System.out.println("[MtcDriver2] Started: port=" + midiPort);
            
        } catch (Exception e) {
            lastError = e.getMessage();
            outputState = "ERROR";
            System.out.println("[MtcDriver2] Start failed: " + e.getMessage());
        }
    }
    
    @Override
    public void stop() {
        running = false;
        outputState = "STOPPED";
        
        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
        
        if (receiver != null) {
            receiver.close();
            receiver = null;
        }
        
        if (device != null) {
            device.close();
            device = null;
        }
        
        System.out.println("[MtcDriver2] Stopped");
    }
    
    @Override
    public void update(Map<String, Object> state) {
        // 不再接收外部 state
    }
    
    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("running", running);
        m.put("outputState", outputState);
        m.put("midiPort", midiPort);
        m.put("qfCounter", qfCounter);
        m.put("timelineSec", timeline.getTimelineSec());
        m.put("timelineState", timeline.getPlayState().name());
        m.put("error", lastError);
        return m;
    }
    
    private void sendLoop() {
        int fps = 25;
        long intervalNs = 1_000_000_000 / fps / 8;
        long nextSendNs = System.nanoTime();
        
        while (running) {
            long nowNs = System.nanoTime();
            
            if (nowNs < nextSendNs) {
                try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                continue;
            }
            
            double timelineSec = timeline.getTimelineSec();
            TimecodeTimeline.PlayState state = timeline.getPlayState();
            
            if (state == TimecodeTimeline.PlayState.STOPPED) {
                outputState = "PAUSED";
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                continue;
            }
            
            outputState = "OUTPUTTING";
            
            int totalFrames = (int) (timelineSec * fps);
            int ff = totalFrames % fps;
            int ss = (totalFrames / fps) % 60;
            int mm = (totalFrames / (fps * 60)) % 60;
            int hh = (totalFrames / (fps * 3600)) % 24;
            
            sendQuarterFrame(hh, mm, ss, ff);
            
            nextSendNs += intervalNs;
        }
    }
    
    private void sendQuarterFrame(int hh, int mm, int ss, int ff) {
        if (receiver == null) return;
        
        int type = qfCounter & 0x0F;
        
        int data;
        switch (type) {
            case 0: data = 0x00 | (ff & 0x0F); break;
            case 1: data = 0x10 | ((ff >> 4) & 0x03); break;
            case 2: data = 0x20 | (ss & 0x0F); break;
            case 3: data = 0x30 | ((ss >> 4) & 0x03); break;
            case 4: data = 0x40 | (mm & 0x0F); break;
            case 5: data = 0x50 | ((mm >> 4) & 0x03); break;
            case 6: data = 0x60 | (hh & 0x0F); break;
            case 7: data = 0x70 | ((hh >> 4) & 0x01) | 0x08; break;
            default: return;
        }
        
        try {
            ShortMessage msg = new ShortMessage();
            msg.setMessage(ShortMessage.TIMING_CLOCK);
            receiver.send(msg, -1);
            
            // QF message: F0 7F <device> 7F 04 <type> <data> F7
            byte[] qf = new byte[] { (byte)0xF0, (byte)0x7F, (byte)0x7F, (byte)0x04, (byte)type, (byte)data, (byte)0xF7 };
            SysexMessage sysex = new SysexMessage();
            sysex.setMessage(qf, qf.length);
            receiver.send(sysex, -1);
            
            qfCounter = (qfCounter + 1) & 0x07;
            
        } catch (Exception e) {
            lastError = e.getMessage();
        }
    }
    
    private String strCfg(String k, String def) {
        Object v = cfg.get(k);
        return v != null ? String.valueOf(v) : def;
    }
}
