package dbclient.sync;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TimecodeSourceResolver - 解析手动 timecodeSource
 * 
 * 职责：
 * - 从 sync.timecodeSource 解析手动指定 source
 * - 验证 player 在线状态
 * - 无效时返回 NO_SOURCE
 * - 不允许 fallback 到 master
 */
public class TimecodeSourceResolver {
    
    private static final TimecodeSourceResolver INSTANCE = new TimecodeSourceResolver();
    
    // 缓存的在线 players 列表
    private volatile List<Map<String, Object>> lastPlayersList = new ArrayList<>();
    
    // 当前解析的 source
    private volatile int currentSource = 0;
    private volatile SourceState state = SourceState.NO_SOURCE;
    
    public enum SourceState {
        NO_SOURCE,      // 未选择 source
        INVALID,       // source 无效（player 不在线）
        VALID          // source 有效
    }
    
    public static TimecodeSourceResolver getInstance() {
        return INSTANCE;
    }
    
    private TimecodeSourceResolver() {}
    
    /**
     * 更新在线 players 列表
     */
    public synchronized void updatePlayers(List<Map<String, Object>> players) {
        this.lastPlayersList = players != null ? new ArrayList<>(players) : new ArrayList<>();
    }
    
    /**
     * 解析 timecodeSource 配置
     * @param timecodeSource 配置值 (0 = 未选择)
     * @return 解析结果
     */
    public ResolveResult resolve(int timecodeSource) {
        this.currentSource = timecodeSource;
        
        // 未选择
        if (timecodeSource <= 0) {
            this.state = SourceState.NO_SOURCE;
            return new ResolveResult(0, null, SourceState.NO_SOURCE, "未选择 timecodeSource");
        }
        
        // 查找选中的 player
        for (Map<String, Object> player : lastPlayersList) {
            Object numObj = player.get("number");
            if (numObj instanceof Number) {
                int playerNum = ((Number) numObj).intValue();
                if (playerNum == timecodeSource) {
                    // 验证 player 是否在线
                    Boolean active = Boolean.TRUE.equals(player.get("active"));
                    if (active) {
                        this.state = SourceState.VALID;
                        return new ResolveResult(timecodeSource, player, SourceState.VALID, "OK");
                    } else {
                        this.state = SourceState.INVALID;
                        return new ResolveResult(timecodeSource, null, SourceState.INVALID, "Player " + timecodeSource + " 不在线");
                    }
                }
            }
        }
        
        // 未找到 player
        this.state = SourceState.INVALID;
        return new ResolveResult(timecodeSource, null, SourceState.INVALID, "Player " + timecodeSource + " 不存在");
    }
    
    /**
     * 获取当前状态
     */
    public SourceState getState() {
        return state;
    }
    
    /**
     * 获取当前解析的 source
     */
    public int getCurrentSource() {
        return currentSource;
    }
    
    /**
     * 解析结果
     */
    public static class ResolveResult {
        public final int requestedSource;
        public final Map<String, Object> player;
        public final SourceState state;
        public final String message;
        
        public ResolveResult(int requestedSource, Map<String, Object> player, SourceState state, String message) {
            this.requestedSource = requestedSource;
            this.player = player;
            this.state = state;
            this.message = message;
        }
        
        public boolean isValid() {
            return state == SourceState.VALID && player != null;
        }
    }
}
