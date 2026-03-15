package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.timecode.TimecodeConsumer;
import dbclient.sync.timecode.TimecodeCore;

import javax.sound.midi.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MtcDriver - MTC 输出驱动
 * 
 * 职责：
 * - 作为 TimecodeConsumer 接收 TimecodeCore 推送的帧
 * - 将帧编码为 MTC Quarter Frame 消息输出
 * 
 * 不处理：
 * - 播放源选择（由 TimecodeCore 处理）
 * - 事件检测（由 TimecodeCore 处理）
 * - 锚点管理（由 TimecodeCore 处理）
 */
public class MtcDriver implements OutputDriver, TimecodeConsumer {
    
    public static final String NAME = "mtc";
    private static final double FRAME_RATE = 25.0;
    private static final long QUARTER_INTERVAL_MS = 1000 / 25;  // 40ms
    
    // 状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean enabled = false;
    private volatile String midiPort = "default";
    private volatile long currentFrame = 0;
    private volatile int qfIndex = 0;  // Quarter Frame index 0-7
    
    // MIDI 输出
    private MidiDevice midiDevice;
    private Receiver midiReceiver;
    
    public MtcDriver() {
    }
    
    @Override
    public String name() {
        return NAME;
    }
    
    /**
     * 启动驱动
     */
    @Override
    public void start(Map<String, Object> config) {
        if (running.get()) return;
        
        if (config != null) {
            enabled = Boolean.TRUE.equals(config.get("enabled"));
            if (config.get("midiPort") instanceof String) {
                midiPort = (String) config.get("midiPort");
            }
        }
        
        if (!enabled) return;
        
        // 枚举并打印所有可用 MIDI 端口
        System.out.println("[MTC] Enumerating MIDI ports for MTC output...");
        MidiDeviceEnumerator.enumerateOutputPorts();
        
        // 打开 MIDI 设备
        try {
            openMidiDevice();
        } catch (Exception e) {
            System.err.println("[MTC] Failed to open MIDI port '" + midiPort + "': " + e.getMessage());
            return;
        }
        
        running.set(true);
    }
    
    /**
     * 停止驱动
     */
    @Override
    public void stop() {
        if (!running.get()) return;
        
        running.set(false);
        closeMidiDevice();
    }
    
    /**
     * 接收播放器状态（传递给 TimecodeCore）
     * 
     * 注意：此方法只传递状态，不作为输出节奏
     */
    @Override
    public void update(Map<String, Object> state) {
        // MtcDriver 不直接处理状态
        // 状态由 TimecodeCore 处理，MtcDriver 只接收 onFrame 回调
    }
    
    /**
     * TimecodeConsumer 回调：接收当前帧并输出
     * 
     * 由 TimecodeCore 以均匀节奏（25fps）调用
     * 每次调用发送 1 个 Quarter Frame（共 8 个组成完整时间码）
     */
    @Override
    public void onFrame(long frame) {
        if (!running.get() || !enabled) return;
        
        this.currentFrame = frame;
        
        // 发送 Quarter Frame
        try {
            int typeAndData = generateQuarterFrame(frame, qfIndex);
            sendQuarterFrame(typeAndData);
            
            qfIndex = (qfIndex + 1) % 8;
        } catch (Exception e) {
            System.err.println("[MTC] Output error: " + e.getMessage());
        }
    }
    
    /**
     * 获取驱动状态
     */
    @Override
    public Map<String, Object> status() {
        return Map.of(
            "enabled", enabled,
            "running", running.get(),
            "fps", FRAME_RATE,
            "midiPort", midiPort,
            "currentFrame", currentFrame,
            "qfIndex", qfIndex
        );
    }
    
    // ============ 私有方法 ============
    
    private int generateQuarterFrame(long frame, int qfIndex) {
        // 帧号转换为 hh:mm:ss:ff
        long totalFrames = frame;
        int ff = (int) (totalFrames % 25);
        long totalSeconds = totalFrames / 25;
        int ss = (int) (totalSeconds % 60);
        int mm = (int) ((totalSeconds / 60) % 60);
        int hh = (int) ((totalSeconds / 3600) % 24);
        
        switch (qfIndex) {
            case 0: return 0x00 | (ff & 0x0F);           // Frame units
            case 1: return 0x10 | ((ff >> 4) & 0x03);   // Frame tens
            case 2: return 0x20 | (ss & 0x0F);           // Seconds units
            case 3: return 0x30 | ((ss >> 4) & 0x07);   // Seconds tens
            case 4: return 0x40 | (mm & 0x0F);           // Minutes units
            case 5: return 0x50 | ((mm >> 4) & 0x07);   // Minutes tens
            case 6: return 0x60 | (hh & 0x0F);           // Hours units
            case 7: return 0x70 | ((hh >> 4) & 0x03) | 0x08;  // Hours tens + 25fps flag
            default: return 0;
        }
    }
    
    private void sendQuarterFrame(int typeAndData) throws Exception {
        if (midiReceiver == null) return;
        
        // Quarter Frame 消息: F1 <typeAndData>
        ShortMessage msg = new ShortMessage();
        msg.setMessage(0xF1, typeAndData, 0);
        midiReceiver.send(msg, -1);
    }
    
    private void openMidiDevice() throws Exception {
        if ("default".equals(midiPort)) {
            // 使用默认 MIDI 输出
            MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
            for (MidiDevice.Info info : infos) {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device.getMaxReceivers() != 0) {
                    midiDevice = device;
                    break;
                }
            }
        }
        
        if (midiDevice == null) {
            throw new Exception("No MIDI output device available");
        }
        
        midiDevice.open();
        midiReceiver = midiDevice.getReceiver();
    }
    
    private void closeMidiDevice() {
        if (midiReceiver != null) {
            midiReceiver.close();
            midiReceiver = null;
        }
        if (midiDevice != null) {
            midiDevice.close();
            midiDevice = null;
        }
    }
}
