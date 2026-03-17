package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.timecode.TimecodeConsumer;
import dbclient.sync.timecode.TimecodeCore;

import javax.sound.midi.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MtcDriver - MTC MIDI 时间码输出 (FINAL - 已稳定)
 *
 * 【核心架构】
 * 1. 本地帧推进：nextFrameToWrite 独立维护
 * 2. 事件重锚：onFrame() 检测跳变，diff > 50 帧时重锚
 * 3. 状态同步：onStateChange() 处理 STOPPED/PLAYING/PAUSED
 * 4. MIDI 输出：burst 发送 8 个 Quarter Frame（每帧一次）
 *
 * 【与 LTC 差异】
 * - MTC：MIDI 输出，25fps 每次 burst 8 个 QF
 * - LTC：音频输出，音频线程本地推进
 */
public class MtcDriver implements OutputDriver, TimecodeConsumer, TimecodeCore.TimecodeStateListener {

    public static final String NAME = "mtc";
    private static final double FRAME_RATE = 25.0;
    private static final long JUMP_THRESHOLD_FRAMES = 50; // 2秒跳变阈值

    // 运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean enabled = false;
    private volatile String midiPort = "default";

    // 本地帧推进状态（核心）
    private volatile long nextFrameToWrite = 0;    // 下一个要发送的帧
    private volatile long lastAnchorFrame = 0;     // 上次锚点（用于跳变检测）
    private volatile String currentState = "STOPPED"; // STOPPED/PLAYING/PAUSED

    // MIDI 输出
    private MidiDevice midiDevice;
    private Receiver midiReceiver;

    public MtcDriver() {}

    @Override
    public String name() { return NAME; }

    @Override
    public void start(Map<String, Object> config) {
        if (running.get()) return;

        if (config != null) {
            enabled = Boolean.TRUE.equals(config.get("enabled"));
            if (config.get("midiPort") instanceof String) midiPort = (String) config.get("midiPort");
        }
        if (!enabled) return;

        try { openMidiDevice(); }
        catch (Exception e) { System.err.println("[MTC] Failed: " + e.getMessage()); return; }

        nextFrameToWrite = 0;
        lastAnchorFrame = 0;
        currentState = "STOPPED";
        running.set(true);

        System.out.println("[MTC] Started");
    }

    @Override
    public void stop() {
        if (!running.get()) return;
        running.set(false);
        closeMidiDevice();
    }

    @Override
    public void update(Map<String, Object> state) {
        // 状态由 onStateChange 处理
    }

    /**
     * TimecodeCore 回调：均匀推送（25fps）
     * 【核心逻辑】burst 发送 8 个 QF，检测跳变重锚
     */
    @Override
    public void onFrame(long frame) {
        if (!running.get() || !enabled) return;

        // 【关键】只在 PLAYING 状态发送
        if (!"PLAYING".equals(currentState)) return;

        // 检测跳变并重锚
        long diff = Math.abs(frame - lastAnchorFrame);
        if (diff > JUMP_THRESHOLD_FRAMES) {
            System.out.println("[MTC] Jump: " + lastAnchorFrame + " -> " + frame);
            nextFrameToWrite = frame;
        }
        lastAnchorFrame = frame;

        // burst 发送当前帧的所有 8 个 Quarter Frame
        try {
            for (int i = 0; i < 8; i++) {
                sendQuarterFrame(nextFrameToWrite, i);
            }
        } catch (Exception e) {
            System.err.println("[MTC] Output error: " + e.getMessage());
        }

        // 推进到下一帧
        nextFrameToWrite++;
    }

    /**
     * 状态变化回调
     */
    @Override
    public void onStateChange(String from, String to, long anchorFrame, long timeNs) {
        System.out.println("[MTC] State: " + from + " -> " + to);
        this.currentState = to;

        switch (to) {
            case "PLAYING":
                nextFrameToWrite = anchorFrame;
                lastAnchorFrame = anchorFrame;
                break;
            case "STOPPED":
                nextFrameToWrite = 0;
                lastAnchorFrame = 0;
                break;
            case "PAUSED":
                // 冻结：onFrame 中不发送，帧号保持不变
                break;
        }
    }

    /**
     * 发送 Quarter Frame MIDI 消息
     */
    private void sendQuarterFrame(long frame, int qfIndex) throws Exception {
        if (midiReceiver == null) return;

        int typeAndData = generateQuarterFrame(frame, qfIndex);
        ShortMessage msg = new ShortMessage();
        msg.setMessage(0xF1, typeAndData, 0);
        midiReceiver.send(msg, -1);
    }

    /**
     * 生成 Quarter Frame 数据字节
     */
    private int generateQuarterFrame(long frame, int qfIndex) {
        long totalFrames = frame;
        int ff = (int) (totalFrames % 25);
        long totalSeconds = totalFrames / 25;
        int ss = (int) (totalSeconds % 60);
        int mm = (int) ((totalSeconds / 60) % 60);
        int hh = (int) ((totalSeconds / 3600) % 24);

        switch (qfIndex) {
            case 0: return 0x00 | (ff & 0x0F);
            case 1: return 0x10 | ((ff >> 4) & 0x03);
            case 2: return 0x20 | (ss & 0x0F);
            case 3: return 0x30 | ((ss >> 4) & 0x07);
            case 4: return 0x40 | (mm & 0x0F);
            case 5: return 0x50 | ((mm >> 4) & 0x07);
            case 6: return 0x60 | (hh & 0x0F);
            case 7: return 0x70 | ((hh >> 4) & 0x03) | 0x08;
            default: return 0;
        }
    }

    private void openMidiDevice() throws Exception {
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();

        if ("default".equals(midiPort)) {
            for (MidiDevice.Info info : infos) {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (device.getMaxReceivers() != 0) {
                    midiDevice = device;
                    break;
                }
            }
        } else {
            for (MidiDevice.Info info : infos) {
                if (info.getName().contains(midiPort) || midiPort.contains(info.getName())) {
                    MidiDevice device = MidiSystem.getMidiDevice(info);
                    if (device.getMaxReceivers() != 0) {
                        midiDevice = device;
                        System.out.println("[MTC] Device: " + info.getName());
                        break;
                    }
                }
            }
        }

        if (midiDevice == null) throw new Exception("No MIDI device: " + midiPort);

        midiDevice.open();
        midiReceiver = midiDevice.getReceiver();
        System.out.println("[MTC] Opened");
    }

    private void closeMidiDevice() {
        if (midiReceiver != null) { midiReceiver.close(); midiReceiver = null; }
        if (midiDevice != null) { midiDevice.close(); midiDevice = null; }
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
            "enabled", enabled,
            "running", running.get(),
            "currentState", currentState,
            "nextFrameToWrite", nextFrameToWrite,
            "fps", FRAME_RATE
        );
    }
}
