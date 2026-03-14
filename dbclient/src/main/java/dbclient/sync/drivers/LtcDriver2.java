package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeTimeline;
import javax.sound.sampled.*;
import java.util.Map;

/**
 * LtcDriver2 - 基于 TimecodeTimeline 的新 LTC 驱动
 */
public class LtcDriver2 implements OutputDriver {
    
    public String name() { return "ltc2"; }
    
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
        
        try {
            sampleRate = intCfg("sampleRate", 48000);
            fps = intCfg("fps", 25);
            double gainDb = numCfg("gainDb", -8.0);
            amp = Math.max(0.01, Math.min(0.95, Math.pow(10.0, gainDb / 20.0)));
            
            fmt = new AudioFormat(sampleRate, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, sampleRate * 2);
            line.start();
            
            frameBuilder = new LtcFrameBuilder2(fps);
            mod = new LtcBmcModulator2(sampleRate, fps);
            
            double samplesPerFrame = (double) sampleRate / fps;
            nextFrameBoundarySample = (long) samplesPerFrame;
            
            running = true;
            outputState = "RUNNING";
            
            audioThread = new Thread(this::audioLoop, "ltc2-audio");
            audioThread.setDaemon(true);
            audioThread.start();
            
            System.out.println("[LtcDriver2] Started: sampleRate=" + sampleRate + " fps=" + fps);
            
        } catch (Exception e) {
            lastError = e.getMessage();
            outputState = "ERROR";
            System.out.println("[LtcDriver2] Start failed: " + e.getMessage());
        }
    }
    
    @Override
    public void stop() {
        running = false;
        outputState = "STOPPED";
        
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        
        if (line != null) {
            try { line.close(); } catch (Exception ignored) {}
            line = null;
        }
        
        System.out.println("[LtcDriver2] Stopped");
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
        m.put("timelineSec", timeline.getTimelineSec());
        m.put("timelineState", timeline.getPlayState().name());
        m.put("error", lastError);
        return m;
    }
    
    private void audioLoop() {
        int bufferSamples = sampleRate / 40;
        byte[] out = new byte[bufferSamples * 4];
        double samplesPerFrame = (double) sampleRate / fps;
        
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
                
                if (!framePrimed || currentSamplePos >= nextFrameBoundarySample) {
                    localFramePosition = (long) (timelineSec * fps);
                    frameInSecond = (int) (localFramePosition % fps);
                    
                    int hh = (int) ((localFramePosition / (fps * 3600)) % 24);
                    int mm = (int) ((localFramePosition / (fps * 60)) % 60);
                    int ss = (int) (localFramePosition % 60);
                    int ff = frameInSecond;
                    
                    boolean[] bits = frameBuilder.build(hh, mm, ss, ff);
                    mod.loadFrame(bits);
                    framePrimed = true;
                    
                    nextFrameBoundarySample = (long) ((localFramePosition + 1) * samplesPerFrame);
                }
                
                double sample = mod.nextSample() * amp;
                sumSq += sample * sample;
                
                short s = (short) (sample * 32767.0);
                out[i * 4] = (byte) (s & 0xff);
                out[i * 4 + 1] = (byte) ((s >> 8) & 0xff);
                out[i * 4 + 2] = (byte) (s & 0xff);
                out[i * 4 + 3] = (byte) ((s >> 8) & 0xff);
            }
            
            localSamplePosition += (long) positionPhase;
            
            double rms = Math.sqrt(sumSq / bufferSamples);
            signalLevel = signalLevel * 0.9 + rms * 0.1;
            
            try {
                line.write(out, 0, out.length);
            } catch (Exception e) {
                lastError = e.getMessage();
                outputState = "ERROR";
                break;
            }
        }
        
        running = false;
        outputState = "STOPPED";
    }
    
    private int intCfg(String k, int def) {
        Object v = cfg.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
    
    private double numCfg(String k, double def) {
        Object v = cfg.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }
    
    static class LtcFrameBuilder2 {
        private final int fps;
        LtcFrameBuilder2(int fps) { this.fps = fps; }
        
        boolean[] build(int hh, int mm, int ss, int ff) {
            boolean[] bits = new boolean[80];
            bits[0] = false;
            for (int i = 1; i <= 14; i++) bits[i] = true;
            bits[15] = false;
            bits[16] = (ff & 1) != 0;
            bits[17] = (ff & 2) != 0;
            bits[18] = (ff & 4) != 0;
            bits[19] = (ff & 8) != 0;
            bits[20] = (ss & 1) != 0;
            bits[21] = (ss & 2) != 0;
            bits[22] = (ss & 4) != 0;
            bits[23] = (ss & 8) != 0;
            bits[24] = (ss & 10) != 0;
            bits[25] = (ss & 20) != 0;
            bits[26] = (ss & 40) != 0;
            bits[27] = (mm & 1) != 0;
            bits[28] = (mm & 2) != 0;
            bits[29] = (mm & 4) != 0;
            bits[30] = (mm & 8) != 0;
            bits[31] = (mm & 10) != 0;
            bits[32] = (mm & 20) != 0;
            bits[33] = (mm & 40) != 0;
            bits[34] = (hh & 1) != 0;
            bits[35] = (hh & 2) != 0;
            bits[36] = (hh & 4) != 0;
            bits[37] = (hh & 8) != 0;
            bits[38] = (hh & 10) != 0;
            bits[39] = (hh & 20) != 0;
            for (int i = 40; i < 64; i++) bits[i] = false;
            bits[64] = false;
            bits[65] = bits[66] = bits[67] = false;
            for (int i = 68; i < 79; i++) bits[i] = true;
            bits[79] = false;
            return bits;
        }
    }
    
    static class LtcBmcModulator2 {
        private final double samplesPerBit;
        private boolean[] bits = new boolean[80];
        private int bitIndex = 0;
        private double sampleInBit = 0.0;
        private double level = 1.0;
        
        LtcBmcModulator2(int sampleRate, int fps) {
            double bitRate = 80.0 * fps;
            this.samplesPerBit = (double) sampleRate / bitRate;
        }
        
        void loadFrame(boolean[] b) {
            if (b == null || b.length != 80) return;
            this.bits = b;
            this.bitIndex = 0;
            this.sampleInBit = 0.0;
            this.level = 1.0;
        }
        
        double nextSample() {
            if (bitIndex >= 80) return 0.0;
            double out = level;
            sampleInBit += 1.0;
            if (sampleInBit >= samplesPerBit) {
                sampleInBit -= samplesPerBit;
                level = -level;
                bitIndex++;
            }
            return out;
        }
    }
}
