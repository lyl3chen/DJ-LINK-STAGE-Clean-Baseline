package dbclient.sync.drivers;

import java.util.Map;

/**
 * AbletonLinkBeatBridge - 已禁用
 * 
 * ========== Ableton Link 稳定版本：仅 BPM 同步 ==========
 * 
 * 当前状态：
 * - PURE_BPM_MODE = true
 * - beat 对齐已禁用，只保留 tempo 同步
 * 
 * 功能：
 * - tempo 同步（ CarabinerLinkEngine 负责）
 * - peer 会话连接
 * 
 * 已禁用（不再启用）：
 * - request-beat-at-time
 * - force-beat-at-time  
 * - PLAY_START / SEEK / PERIODIC / MANUAL_RESYNC beat 对齐
 * - start/stop sync
 * 
 * 结论：Ableton Link 仅作为 BPM 同步桥，不承担 Beat/Measure 对齐
 */
public class AbletonLinkBeatBridge {
    
    // 禁用状态
    private static final boolean DISABLED = true;
    
    private final CarabinerLinkEngine carabinerEngine;
    
    public AbletonLinkBeatBridge(CarabinerLinkEngine carabinerEngine) {
        this.carabinerEngine = carabinerEngine;
    }
    
    public void updateMasterState(Map<String, Object> state) {
        // 已禁用，不做任何 beat 同步
    }
    
    public void manualResync() {
        System.out.println("[AbletonLinkBeatBridge] DISABLED - Ableton Link 仅作为 BPM 同步桥");
    }
}
