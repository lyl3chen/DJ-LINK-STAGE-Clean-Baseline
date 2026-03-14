package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeTimeline;
import javax.sound.midi.*;
import java.util.Map;

/**
 * MtcDriver2 - 基于 TimecodeTimeline 的新 MTC 驱动
 */
public class MtcDriver2 implements OutputDriver {
    
    public String name() { return "mtc"; }
    
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
    private volatile long messagesSent = 0;
    
    @Override
    public void start(Map<String, Object> config) {
        if (running) return;
        
        cfg = config != null ? new java.util.LinkedHashMap<>(config) : new java.util.LinkedHashMap<>();
        
        System.out.println("[MTC2] start() called with config: " + cfg);
        
        try {
            midiPort = strCfg("midiPort", "default");
            
            System.out.println("[MTC2] Looking for MIDI port: " + midiPort);
            
            // 枚举 MIDI 设备
            MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
            System.out.println("[MTC2] Available MIDI devices: " + infos.length);
            
            device = null;
            for (MidiDevice.Info info : infos) {
                System.out.println("[MTC2]   MIDI: " + info.getName() + " (" + info.getVendor() + ")");
                if (info.getName().contains(midiPort) || info.getName().equals(midiPort)) {
                    try {
                        device = MidiSystem.getMidiDevice(info);
                        System.out.println("[MTC2] Found matching MIDI device: " + info.getName());
                        break;
                    } catch (Exception ex) {
                        System.out.println("[MTC2]   Failed to open: " + ex.getMessage());
                    }
                }
            }
            
            // 如果没找到，尝试默认设备
            if (device == null && infos.length > 0) {
                for (MidiDevice.Info info : infos) {
                    try {
                        device = MidiSystem.getMidiDevice(info);
                        if (device.getMaxTransmitters() != 0 || device.getMaxReceivers() != 0) {
                            System.out.println("[MTC2] Using default MIDI device: " + info.getName());
                            break;
                        }
                        device = null;
                    } catch (Exception ignored) {}
                }
            }
            
            if (device == null) {
                throw new Exception("No MIDI device could be opened");
            }
            
            device.open();
            receiver = device.getReceiver();
            
            System.out.println("[MTC2] MIDI device opened: " + device.getDeviceInfo().getName());
            
            running = true;
            outputState = "RUNNING";
            messagesSent = 0;
            
            sendThread = new Thread(this::sendLoop, "mtc2-send");
            sendThread.setDaemon(true);
            sendThread.setPriority(Thread.MAX_PRIORITY);
            sendThread.start();
            
            System.out.println("[MTC2] Started successfully");
            
        } catch (Exception e) {
            lastError = e.getMessage();
            outputState = "ERROR";
            System.out.println("[MTC2] Start failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void stop() {
        running = false;
        outputState = "STOPPED";
        System.out.println("[MTC2] stop() called");
        
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
        
        System.out.println("[MTC2] Stopped");
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
        m.put("messagesSent", messagesSent);
        m.put("timelineSec", timeline.getTimelineSec());
        m.put("timelineState", timeline.getPlayState().name());
        m.put("deviceOpened", device != null && device.isOpen());
        m.put("receiverReady", receiver != null);
        m.put("messageType", "MTC Quarter Frame (F0 7F 7F 04 xx xx F7)");
        m.put("sendsFullFrame", false);  // Currently only QF, no Full Frame
        m.put("error", lastError);
        return m;
    }
    
    private void sendLoop() {
        int fps = 25;
        long intervalNs = 1_000_000_000 / fps / 8;
        long nextSendNs = System.nanoTime();
        
        System.out.println("[MTC2] sendLoop started, intervalNs=" + intervalNs);
        
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
            
            if (messagesSent % 100 == 0 && messagesSent > 0) {
                System.out.println("[MTC2] Sent " + messagesSent + " messages");
            }
            
            nextSendNs += intervalNs;
        }
        
        System.out.println("[MTC2] sendLoop exited");
    }
    
    private void sendQuarterFrame(int hh, int mm, int ss, int ff) {
        if (receiver == null) {
            System.out.println("[MTC2] receiver is null!");
            return;
        }
        
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
            // MIDI Quarter Frame: F0 7F 7F 04 <type> <data> F7
            byte[] qf = new byte[] { 
                (byte)0xF0, (byte)0x7F, (byte)0x7F, (byte)0x04, 
                (byte)type, (byte)data, (byte)0xF7 
            };
            
            // Log first few messages to verify format
            if (messagesSent < 16) {
                String hex = String.format("F0 7F 7F 04 %02X %02X F7", type, data);
                System.out.println("[MTC2] QF[" + messagesSent + "] type=" + type + " data=" + data + " hex=" + hex + " tc=" + String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff));
            }
            
            SysexMessage msg = new SysexMessage();
            msg.setMessage(qf, qf.length);
            receiver.send(msg, -1);
            
            messagesSent++;
            qfCounter = (qfCounter + 1) & 0x07;
            
        } catch (Exception e) {
            lastError = e.getMessage();
            System.out.println("[MTC2] Send failed: " + e.getMessage());
        }
    }
    
    private String strCfg(String k, String def) {
        Object v = cfg.get(k);
        return v != null ? String.valueOf(v) : def;
    }
}
