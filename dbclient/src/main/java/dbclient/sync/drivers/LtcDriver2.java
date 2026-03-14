package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeTimeline;
import javax.sound.sampled.*;
import java.util.Map;

/**
 * LtcDriver2 - 基于 TimecodeTimeline 的新 LTC 驱动
 */
public class LtcDriver2 implements OutputDriver {
    
    public String name() { return "ltc"; }
    
    private volatile boolean running = false;
    private Thread audioThread = null;
    private Map<String, Object> cfg = new java.util.LinkedHashMap<>();
    private final TimecodeTimeline timeline = TimecodeTimeline.getInstance();
    
    private int sampleRate = 48000;
    private int fps = 25;
    private double amp = 0.3;
    private AudioFormat fmt;
    private SourceDataLine line = null;
    
    private volatile String outputState = "IDLE";
    private volatile double signalLevel = 0.0;
    private volatile int frameInSecond = 0;
    private volatile String lastError = null;
    
    // 输出统计
    private volatile long samplesWritten = 0;
    private volatile long framesOutput = 0;
    
    private long localSamplePosition = 0;
    private long localFramePosition = 0;
    private long nextFrameBoundarySample = 0;
    private boolean framePrimed = false;
    
    private LtcFrameBuilder2 frameBuilder;
    private LtcBmcModulator2 mod;
    
    @Override
    public void start(Map<String, Object> config) {
        if (running) return;
        
        cfg = config != null ? new java.util.LinkedHashMap<>(config) : new java.util.LinkedHashMap<>();
        
        System.out.println("[LTC2] start() called with config: " + cfg);
        
        try {
            sampleRate = intCfg("sampleRate", 48000);
            fps = intCfg("fps", 25);
            double gainDb = numCfg("gainDb", -8.0);
            String deviceName = strCfg("deviceName", "default");
            
            amp = Math.max(0.01, Math.min(0.95, Math.pow(10.0, gainDb / 20.0)));
            
            System.out.println("[LTC2] sampleRate=" + sampleRate + " fps=" + fps + " device=" + deviceName + " gainDb=" + gainDb);
            
            fmt = new AudioFormat(sampleRate, 16, 1, true, false); // MONO to match reference
            
            // 尝试打开设备
            line = null;
            
            // 先尝试默认设备
            try {
                line = AudioSystem.getSourceDataLine(fmt);
                System.out.println("[LTC2] Got default line: " + line);
            } catch (Exception e) {
                System.out.println("[LTC2] Default line failed: " + e.getMessage());
            }
            
            // 尝试枚举设备
            if (line == null) {
                try {
                    Mixer.Info[] mixers = AudioSystem.getMixerInfo();
                    System.out.println("[LTC2] Available mixers: " + mixers.length);
                    for (Mixer.Info mi : mixers) {
                        System.out.println("[LTC2]   Mixer: " + mi.getName());
                        if (deviceName != null && (mi.getName().contains(deviceName) || mi.getName().equals(deviceName))) {
                            try {
                                line = AudioSystem.getSourceDataLine(fmt, mi);
                                System.out.println("[LTC2] Found matching device: " + mi.getName());
                                break;
                            } catch (Exception ex) {
                                System.out.println("[LTC2]   Failed to open: " + ex.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[LTC2] Mixer enumeration failed: " + e.getMessage());
                }
            }
            
            if (line == null) {
                throw new Exception("No audio device could be opened");
            }
            
            line.open(fmt, sampleRate * 2);
            line.start();
            System.out.println("[LTC2] line.start() called, isRunning=" + line.isRunning() + " (may still work)");
            
            // 即使 isRunning=false 也尝试写入，有时 Java Sound 会延迟启动
            System.out.println("[LTC2] Line opened and started: " + line.isRunning());
            
            frameBuilder = new LtcFrameBuilder2();
            double bitRate = 80.0 * fps; int bitRateInt = (int) bitRate; mod = new LtcBmcModulator2(sampleRate, bitRateInt);
            
            double samplesPerFrame = (double) sampleRate / fps;
            nextFrameBoundarySample = (long) samplesPerFrame;
            
            running = true;
            outputState = "RUNNING";
            samplesWritten = 0;
            
            audioThread = new Thread(this::audioLoop, "ltc2-audio");
            audioThread.setDaemon(true);
            audioThread.start();
            
            System.out.println("[LTC2] Started successfully");
            
        } catch (Exception e) {
            lastError = e.getMessage();
            outputState = "ERROR";
            System.out.println("[LTC2] Start failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void stop() {
        running = false;
        outputState = "STOPPED";
        System.out.println("[LTC2] stop() called");
        
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        
        if (line != null) {
            try { line.close(); } catch (Exception ignored) {}
            line = null;
        }
        
        System.out.println("[LTC2] Stopped");
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
        m.put("sampleRate", sampleRate);
        m.put("fps", fps);
        m.put("frame", frameInSecond);
        m.put("signalLevel", signalLevel);
        m.put("samplesWritten", samplesWritten);
        m.put("framesOutput", framesOutput);
        m.put("timelineSec", timeline.getTimelineSec());
        m.put("timelineState", timeline.getPlayState().name());
        m.put("lineOpened", line != null && line.isOpen());
        m.put("lineRunning", line != null && line.isRunning());
        m.put("deviceName", strCfg("deviceName", "default"));
        // Audio format details
        if (fmt != null) {
            m.put("audioFormat", fmt.getSampleRate() + "Hz/" + fmt.getSampleSizeInBits() + "bit/" + fmt.getChannels() + "ch/" + (fmt.isBigEndian() ? "BE" : "LE"));
        }
        m.put("channelMode", "stereo");
        m.put("error", lastError);
        return m;
    }
    
    private void audioLoop() {
        int bufferSamples = sampleRate / 40;
        byte[] out = new byte[bufferSamples * 2]; // MONO
        double samplesPerFrame = (double) sampleRate / fps;
        
        System.out.println("[LTC2] audioLoop started, bufferSamples=" + bufferSamples);
        
        while (running && line != null && line.isOpen()) {
            outputState = "OUTPUTTING";
            
            double timelineSec = timeline.getTimelineSec();
            TimecodeTimeline.PlayState state = timeline.getPlayState();
            
            if (state == TimecodeTimeline.PlayState.STOPPED) {
                outputState = "PAUSED";
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                continue;
            }
            
            double sumSq = 0.0;
            double positionPhase = 0.0;
            
            for (int i = 0; i < bufferSamples; i++) {
                positionPhase += 1.0;
                long currentSamplePos = localSamplePosition + (long) positionPhase;
                
                // Check if we need to load a new frame
                if (framePrimed && currentSamplePos < nextFrameBoundarySample) {
                    // Stay with current frame
                } else {
                    // Load new frame
                    localFramePosition = (long) (timelineSec * fps);
                    frameInSecond = (int) (localFramePosition % fps);
                    
                    int hh = (int) ((localFramePosition / (fps * 3600)) % 24);
                    int mm = (int) ((localFramePosition / (fps * 60)) % 60);
                    int ss = (int) (localFramePosition % 60);
                    int ff = frameInSecond;
                    
                    if (framesOutput < 10 || framesOutput % 100 == 0) {
                        System.out.println("[LTC2] LOAD FRAME: pos=" + currentSamplePos + " boundary=" + nextFrameBoundarySample + " tc=" + String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff));
                    }
                    
                    boolean[] bits = frameBuilder.build(hh, mm, ss, ff);
                    if (framesOutput < 10) {
                        String bitStr = "";
                        for (int bi = 0; bi < 80; bi++) bitStr += bits[bi] ? "1" : "0";
                        System.out.println("[LTC2] FRAME: tc=" + String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff) + " bits[0-15]=" + bitStr.substring(0, 16));
                    }
                    mod.loadFrame(bits);
                    framePrimed = true;
                    framesOutput++;
                    
                    nextFrameBoundarySample = (long) ((localFramePosition + 1) * samplesPerFrame);
                }
                
                double sample = mod.nextSample() * amp;
                sumSq += sample * sample;
                
                // Log first few samples of each buffer to verify output
                if (framesOutput <= 3 && i < 5) {
                    System.out.println("[LTC2] sample[" + i + "]=" + sample + " (raw=" + (short)(sample * 32767) + ")");
                }
                
                short s = (short) (sample * 32767.0);
                out[i * 2] = (byte) (s & 0xff);
                out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
                // mono: removed second channel
                // mono
            }
            
            localSamplePosition += (long) positionPhase;
            
            double rms = Math.sqrt(sumSq / bufferSamples);
            signalLevel = signalLevel * 0.9 + rms * 0.1;
            
            try {
                int written = line.write(out, 0, out.length);
                samplesWritten += written / 2;  // 4 bytes per sample
                
                if (samplesWritten % 10000 == 0) {
                    System.out.println("[LTC2] Written " + samplesWritten + " samples, level=" + signalLevel);
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                outputState = "ERROR";
                System.out.println("[LTC2] Write failed: " + e.getMessage());
                break;
            }
        }
        
        running = false;
        outputState = "STOPPED";
        System.out.println("[LTC2] audioLoop exited");
    }
    
    private int intCfg(String k, int def) {
        Object v = cfg.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
    
    private double numCfg(String k, double def) {
        Object v = cfg.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }
    
    private String strCfg(String k, String def) {
        Object v = cfg.get(k);
        return v != null ? String.valueOf(v) : def;
    }
    
    // 标准 LTC 80-bit frame 格式
    static class LtcFrameBuilder2 {
        boolean[] build(int hh, int mm, int ss, int ff) {
            boolean[] bits = new boolean[80];
            
            // Frame (bits 0-15)
            // Frame units (bits 0-3)
            bits[0] = (ff % 10 & 1) != 0;
            bits[1] = (ff % 10 & 2) != 0;
            bits[2] = (ff % 10 & 4) != 0;
            bits[3] = (ff % 10 & 8) != 0;
            // Frame tens (bits 8-9, 2 bits for 00-02)
            bits[8] = ((ff / 10) & 1) != 0;
            bits[9] = ((ff / 10) & 2) != 0;
            // Sync (bits 10-15): 001111
            bits[10] = false;
            bits[11] = false;
            bits[12] = true;
            bits[13] = true;
            bits[14] = true;
            bits[15] = true;
            
            // Seconds (bits 16-23)
            bits[16] = (ss % 10 & 1) != 0;
            bits[17] = (ss % 10 & 2) != 0;
            bits[18] = (ss % 10 & 4) != 0;
            bits[19] = (ss % 10 & 8) != 0;
            bits[20] = ((ss / 10) & 1) != 0;
            bits[21] = ((ss / 10) & 2) != 0;
            bits[22] = ((ss / 10) & 4) != 0;
            
            // Minutes (bits 24-31)
            bits[24] = (mm % 10 & 1) != 0;
            bits[25] = (mm % 10 & 2) != 0;
            bits[26] = (mm % 10 & 4) != 0;
            bits[27] = (mm % 10 & 8) != 0;
            bits[28] = ((mm / 10) & 1) != 0;
            bits[29] = ((mm / 10) & 2) != 0;
            bits[30] = ((mm / 10) & 4) != 0;
            
            // Hours (bits 32-39)
            bits[32] = (hh % 10 & 1) != 0;
            bits[33] = (hh % 10 & 2) != 0;
            bits[34] = (hh % 10 & 4) != 0;
            bits[35] = (hh % 10 & 8) != 0;
            bits[36] = ((hh / 10) & 1) != 0;
            bits[37] = ((hh / 10) & 2) != 0;
            
            // User bits (bits 40-63) - all zero
            
            // Sync word (bits 64-79): 0011111111111101
            int[] syncWireOrder = new int[] {0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,1};
            for (int i = 0; i < 16; i++) bits[64 + i] = syncWireOrder[i] == 1;
            
            return bits;
        }
    }
    
    // BMC (Biphase Mark Code) modulator - 从旧实现复制，已验证有效
    static class LtcBmcModulator2 {
        private final double samplesPerBit;
        private final double halfSamples;
        private boolean[] bits = new boolean[80];
        private int bitIndex = 0;
        private double sampleInBit = 0.0;
        private double level = -1.0;
        private boolean empty = true;

        LtcBmcModulator2(int sampleRate, int bitRate) {
            this.samplesPerBit = sampleRate / bitRate;
            this.halfSamples = this.samplesPerBit * 0.5;
        }

        void loadFrame(boolean[] b) {
            if (b == null || b.length != 80) return;
            this.bits = b.clone();
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
