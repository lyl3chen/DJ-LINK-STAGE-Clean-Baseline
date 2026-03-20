package dbclient.sync.drivers;

import java.util.Map;

/**
 * AbletonLinkBeatBridge - 已禁用
 * 
 * beat 对齐已禁用，只保留 tempo 同步
 * PURE_BPM_MODE = true
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
        System.out.println("[AbletonLinkBeatBridge] DISABLED");
    }
}
