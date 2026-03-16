package dbclient.input;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 输入源管理器
 * 管理多个 SourceInput，提供统一访问入口
 */
public class SourceInputManager {

    private final Map<String, SourceInput> sources = new ConcurrentHashMap<>();
    private volatile SourceInput activeSource;
    private volatile String activeSourceName;

    /**
     * 注册输入源
     *
     * @param name 源名称标识
     * @param source 输入源实例
     */
    public void registerSource(String name, SourceInput source) {
        sources.put(name, source);
        // 第一个注册的源设为默认激活
        if (activeSource == null) {
            activeSource = source;
            activeSourceName = name;
        }
    }

    /**
     * 切换到指定输入源
     *
     * @param name 源名称标识
     * @return 是否切换成功
     */
    public boolean switchToSource(String name) {
        SourceInput source = sources.get(name);
        if (source == null) {
            return false;
        }
        activeSource = source;
        activeSourceName = name;
        return true;
    }

    /**
     * 获取当前激活的输入源
     */
    public SourceInput getActiveSource() {
        return activeSource;
    }

    /**
     * 获取当前激活源的名称
     */
    public String getActiveSourceName() {
        return activeSourceName;
    }

    /**
     * 获取指定名称的输入源
     */
    public SourceInput getSource(String name) {
        return sources.get(name);
    }

    /**
     * 获取所有已注册的源名称
     */
    public Map<String, SourceInput> getAllSources() {
        return new ConcurrentHashMap<>(sources);
    }

    /**
     * 检查指定源是否已注册
     */
    public boolean hasSource(String name) {
        return sources.containsKey(name);
    }

    /**
     * 获取当前源的统一状态数据（用于向上层传递）
     */
    public Map<String, Object> getCurrentState() {
        SourceInput source = activeSource;
        if (source == null) {
            return Map.of(
                "sourceType", "none",
                "sourceState", "OFFLINE",
                "sourcePlayer", 0,
                "sourcePlaying", false
            );
        }

        return Map.of(
            "sourceType", source.getType(),
            "sourceState", source.getState(),
            "sourcePlayer", source.getSourcePlayerNumber(),
            "sourcePlaying", source.isPlaying(),
            "sourceTimeSec", source.getSourceTimeSec(),
            "sourceBpm", source.getSourceBpm(),
            "sourcePitch", source.getSourcePitch()
        );
    }
}
