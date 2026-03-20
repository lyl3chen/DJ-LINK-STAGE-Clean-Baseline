package dbclient.sync.drivers;

import java.util.Map;

/**
 * Ableton Link Beat/Measure Bridge - 首拍对齐模式
 * 
 * 只在以下时机触发首拍对齐：
 * - PLAY_START: CDJ 开始播放
 * - SEEK: 音轨位置跳跃
 * - MANUAL_RESYNC: 手动触发
 * 
 * 不触发：
 * - BPM_CHANGE
 * - PERIODIC_M16/M32/M48
 * 
 * 命令策略：
 * - request-beat-at-time: 首拍对齐（默认）
 * - force-beat-at-time: request 失败后的 fallback
 */
public class AbletonLinkBeatBridge {
    
    // ========== 首拍对齐模式 ==========
    private static final boolean FIRST_BEAT_ALIGN_MODE = true;
    
    // ========== 触发条件 ==========
    private static final double LOOKAHEAD_BEATS = 8.0;  // 向前看拍数
    private static final long COOLDOWN_MS = 3000;        // 冷却时间
    
    // ========== 主源状态 ==========
    private double masterBeat = 0.0;
    private double masterQuantum = 4.0;
    private boolean sourcePlaying = false;
    private double masterBpm = 120.0;
    private double sourcePitchPct = 0.0;
    
    // ========== 上一帧状态 ==========
    private boolean lastSourcePlaying = false;
    private double lastSourceTimeMs = 0.0;
    private double lastMasterBeat = 0.0;
    private double lastMasterBpm = 120.0;
    
    // ========== 同步状态 ==========
    private long lastSyncTimeMs = 0;
    private double lastTargetBeat = -1;
    
    // ========== Carabiner 引擎 ==========
    private final CarabinerLinkEngine carabinerEngine;
    
    public AbletonLinkBeatBridge(CarabinerLinkEngine carabinerEngine) {
        this.carabinerEngine = carabinerEngine;
    }
    
    /**
     * 更新主源状态（每帧调用）
     */
    public void updateMasterState(Map<String, Object> state) {
        // 保存上一帧状态
        this.lastSourcePlaying = this.sourcePlaying;
        this.lastSourceTimeMs = this.masterBeat;
        this.lastMasterBeat = this.masterBeat;
        this.lastMasterBpm = this.masterBpm;
        
        // 更新当前状态
        Object beat = state.get("masterBeat");
        Object quantum = state.get("masterQuantum");
        Object playing = state.get("sourcePlaying");
        Object bpm = state.get("masterBpm");
        Object pitch = state.get("sourcePitchPct");
        
        this.masterBeat = beat instanceof Number ? ((Number) beat).doubleValue() : this.masterBeat;
        this.masterQuantum = quantum instanceof Number ? ((Number) quantum).doubleValue() : this.masterQuantum;
        this.sourcePlaying = playing instanceof Boolean ? (Boolean) playing : this.sourcePlaying;
        this.masterBpm = bpm instanceof Number ? ((Number) bpm).doubleValue() : this.masterBpm;
        this.sourcePitchPct = pitch instanceof Number ? ((Number) pitch).doubleValue() : this.sourcePitchPct;
        
        if (FIRST_BEAT_ALIGN_MODE) {
            checkAndTriggerSync();
        }
    }
    
    /**
     * 检查是否触发首拍对齐
     */
    private void checkAndTriggerSync() {
        long nowMs = System.currentTimeMillis();
        
        // 1. 检测 PLAY_START 事件
        boolean playStart = !lastSourcePlaying && sourcePlaying;
        
        // 2. 检测 SEEK 事件（时间跳跃 > 1秒）
        double timeJumpMs = Math.abs(masterBeat - lastSourceTimeMs) * 60_000 / masterBpm;
        boolean seek = timeJumpMs > 1000;
        
        // 3. 触发条件
        boolean shouldSync = playStart || seek;
        
        if (!shouldSync) {
            return;
        }
        
        // 冷却检查
        if (nowMs - lastSyncTimeMs < COOLDOWN_MS) {
            System.out.println("[AbletonLinkBeatBridge] SYNC: blocked by cooldown, last=" + (nowMs - lastSyncTimeMs) + "ms");
            return;
        }
        
        // 计算目标 beat
        double targetBeat = calculateTargetBeat();
        
        // 去重检查
        if (Math.abs(targetBeat - lastTargetBeat) < 1.0) {
            System.out.println("[AbletonLinkBeatBridge] SYNC: blocked by deduplication, targetBeat=" + targetBeat + ", last=" + lastTargetBeat);
            return;
        }
        
        // 触发同步
        triggerSync(targetBeat, playStart ? "PLAY_START" : "SEEK");
    }
    
    /**
     * 计算目标 beat（首拍对齐）
     */
    private double calculateTargetBeat() {
        // 计算下一个 measure 边界的 beat
        double currentMeasure = Math.floor(masterBeat / masterQuantum);
        double targetMeasure = currentMeasure + 1;  // 下一小节
        double targetBeat = targetMeasure * masterQuantum;
        
        // 如果当前已经是小节开头，跳到下一个小节
        double beatInMeasure = masterBeat % masterQuantum;
        if (beatInMeasure < LOOKAHEAD_BEATS) {
            // 在小节开头附近，对齐到当前小节
            targetBeat = (currentMeasure + 1) * masterQuantum;
        } else {
            // 对齐到下一个小节
            targetBeat = (currentMeasure + 2) * masterQuantum;
        }
        
        return targetBeat;
    }
    
    /**
     * 触发同步
     */
    private void triggerSync(double targetBeat, String eventType) {
        long nowUs = System.currentTimeMillis() * 1000;
        
        // 计算到达目标 beat 的时间（基于有效 BPM）
        double beatsUntil = targetBeat - masterBeat;
        double msPerBeat = 60_000.0 / masterBpm;
        double deltaMs = beatsUntil * msPerBeat;
        long whenUs = (long) (nowUs + deltaMs * 1000);
        
        System.out.println("[AbletonLinkBeatBridge] SYNC: event=" + eventType + 
            ", masterBeat=" + masterBeat + ", targetBeat=" + targetBeat + 
            ", deltaMs=" + deltaMs + ", bpm=" + masterBpm);
        
        // 发送 request-beat-at-time
        carabinerEngine.sendRequestBeatAtTime(targetBeat, whenUs);
        
        // 更新同步状态
        lastSyncTimeMs = System.currentTimeMillis();
        lastTargetBeat = targetBeat;
    }
    
    /**
     * 手动触发重同步
     */
    public void manualResync() {
        if (!FIRST_BEAT_ALIGN_MODE) {
            return;
        }
        
        long nowMs = System.currentTimeMillis();
        
        // 冷却检查
        if (nowMs - lastSyncTimeMs < COOLDOWN_MS) {
            System.out.println("[AbletonLinkBeatBridge] MANUAL_RESYNC: blocked by cooldown");
            return;
        }
        
        double targetBeat = calculateTargetBeat();
        System.out.println("[AbletonLinkBeatBridge] MANUAL_RESYNC: targetBeat=" + targetBeat);
        
        triggerSync(targetBeat, "MANUAL_RESYNC");
    }
    
    // Debug
    public double getMasterBeat() { return masterBeat; }
    public boolean isSourcePlaying() { return sourcePlaying; }
}
