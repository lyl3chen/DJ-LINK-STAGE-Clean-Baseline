package dbclient.input;

import dbclient.media.model.TrackInfo;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/**
 * DJ Link 输入源适配器
 * 包装现有的 beat-link/djlink-service，适配为 SourceInput 接口
 */
public class DjLinkSourceInput implements SourceInput {

    // 通过反射访问 djlink.DeviceManager
    private static final String DJLINK_CLASS = "djlink.DeviceManager";
    private Object deviceManager;
    private Method getPlayersStateMethod;
    private Method getTrackMetadataMethod;

    private volatile String selectedPlayer = "1";

    public DjLinkSourceInput() {
        try {
            Class<?> dmClass = Class.forName(DJLINK_CLASS);
            Method getInstanceMethod = dmClass.getMethod("getInstance");
            this.deviceManager = getInstanceMethod.invoke(null);
            this.getPlayersStateMethod = dmClass.getMethod("getPlayersState");
            this.getTrackMetadataMethod = dmClass.getMethod("getTrackMetadata", int.class);
        } catch (Exception e) {
            System.err.println("[DjLinkSourceInput] Failed to initialize: " + e.getMessage());
        }
    }

    @Override
    public String getType() {
        return "djlink";
    }

    @Override
    public String getDisplayName() {
        return "DJ Link (CDJ)";
    }

    @Override
    public String getState() {
        Map<String, Object> player = getSelectedPlayer();
        if (player == null) {
            return "OFFLINE";
        }

        boolean active = Boolean.TRUE.equals(player.get("active"));
        boolean playing = Boolean.TRUE.equals(player.get("playing"));

        if (!active) {
            return "OFFLINE";
        }
        if (playing) {
            return "PLAYING";
        }

        // 播放中：playing=true
        // 暂停：playing=false，但有有效曲目和时间（已加载曲目且未回到起点）
        // 停止：playing=false，且没有曲目或时间在起点

        // 判断 STOPPED vs PAUSED
        // 关键字段：
        // - currentTimeMs: 当前时间（毫秒）
        // - beat: 当前 beat 编号（>0 表示已启动）
        // - trackId: 有曲目ID表示已加载

        Number beat = (Number) player.get("beat");
        Number timeMs = (Number) player.get("currentTimeMs");
        String trackId = (String) player.get("trackId");
        if (trackId == null || trackId.isEmpty()) {
            Object trackObj = player.get("track");
            if (trackObj instanceof Map) {
                Map trackMap = (Map) trackObj;
                Object nestedTrackId = trackMap.get("trackId");
                if (nestedTrackId instanceof String) {
                    trackId = (String) nestedTrackId;
                }
            }
        }

        double nowMs = timeMs != null ? timeMs.doubleValue() : 0;
        double beatNum = beat != null ? beat.doubleValue() : 0;

        // 有曲目 + (beat > 0 或 时间 > 100ms) = 暂停
        // 否则 = 停止
        boolean hasTrack = trackId != null && !trackId.isEmpty();
        boolean hasPosition = beatNum > 0 || nowMs > 100;

        if (hasTrack && hasPosition) {
            return "PAUSED";
        }
        return "STOPPED";
    }

    /**
     * 获取调试信息（供现场核对）
     */
    public Map<String, Object> getDebugInfo() {
        Map<String, Object> player = getSelectedPlayer();
        Map<String, Object> debug = new java.util.HashMap<>();

        if (player == null) {
            debug.put("status", "OFFLINE");
            debug.put("reason", "player is null");
            return debug;
        }

        boolean active = Boolean.TRUE.equals(player.get("active"));
        boolean playing = Boolean.TRUE.equals(player.get("playing"));
        Number beat = (Number) player.get("beat");
        Number timeMs = (Number) player.get("currentTimeMs");
        String trackId = (String) player.get("trackId");
        Number bpm = (Number) player.get("bpm");
        Number pitch = (Number) player.get("pitch");

        debug.put("active", active);
        debug.put("playing", playing);
        debug.put("beat", beat);
        debug.put("currentTimeMs", timeMs);
        debug.put("trackId", trackId);
        debug.put("bpm", bpm);
        debug.put("pitch", pitch);

        // 状态判定详情
        if (!active) {
            debug.put("status", "OFFLINE");
            debug.put("reason", "!active");
        } else if (playing) {
            debug.put("status", "PLAYING");
            debug.put("reason", "playing=true");
        } else {
            boolean hasTrack = trackId != null && !trackId.isEmpty();
            double nowMs = timeMs != null ? timeMs.doubleValue() : 0;
            double beatNum = beat != null ? beat.doubleValue() : 0;
            boolean hasPosition = beatNum > 0 || nowMs > 100;

            debug.put("hasTrack", hasTrack);
            debug.put("hasPosition", hasPosition);
            debug.put("beatNum", beatNum);
            debug.put("timeMs", nowMs);

            if (hasTrack && hasPosition) {
                debug.put("status", "PAUSED");
                debug.put("reason", "hasTrack && hasPosition");
            } else {
                debug.put("status", "STOPPED");
                debug.put("reason", "!(hasTrack && hasPosition)");
            }
        }

        return debug;
    }

    @Override
    public boolean isOnline() {
        Map<String, Object> player = getSelectedPlayer();
        return player != null && Boolean.TRUE.equals(player.get("active"));
    }

    @Override
    public double getSourceTimeSec() {
        Map<String, Object> player = getSelectedPlayer();
        if (player == null) return 0.0;

        // 时间字段优先级（与后端 DeviceManager.getAiPlayers 一致）：
        // 1. currentTimeMs - TimeFinder 真实传输时间（优先）
        // 2. beatTimeMs - BeatGrid 推导时间（兜底）

        Number timeMs = (Number) player.get("currentTimeMs");
        if (timeMs != null && timeMs.doubleValue() > 0) {
            return timeMs.doubleValue() / 1000.0;
        }

        // 兜底：用 beatTimeMs
        Number beatTimeMs = (Number) player.get("beatTimeMs");
        if (beatTimeMs != null && beatTimeMs.doubleValue() > 0) {
            return beatTimeMs.doubleValue() / 1000.0;
        }

        return 0.0;
    }

    /**
     * 获取时间来源标记（供调试 UI 显示）
     */
    public String getTimeSource() {
        Map<String, Object> player = getSelectedPlayer();
        if (player == null) return "NONE";

        Number timeMs = (Number) player.get("currentTimeMs");
        Number beatTimeMs = (Number) player.get("beatTimeMs");

        if (timeMs != null && timeMs.doubleValue() > 0) {
            return "TimeFinder";
        } else if (beatTimeMs != null && beatTimeMs.doubleValue() > 0) {
            return "BeatGrid";
        }
        return "NONE";
    }

    @Override
    public double getSourceFrameRate() {
        return 25.0;
    }

    @Override
    public double getSourceBpm() {
        Map<String, Object> player = getSelectedPlayer();
        if (player == null) return 0.0;

        Number bpm = (Number) player.get("bpm");
        return bpm != null ? bpm.doubleValue() : 0.0;
    }

    @Override
    public double getSourcePitch() {
        Map<String, Object> player = getSelectedPlayer();
        if (player == null) return 1.0;

        Number pitch = (Number) player.get("pitch");
        return pitch != null ? pitch.doubleValue() : 1.0;
    }

    @Override
    public TrackInfo getCurrentTrack() {
        Map<String, Object> player = getSelectedPlayer();
        if (player == null) return null;

        String trackId = (String) player.get("trackId");
        if (trackId == null || trackId.isEmpty()) {
            return null;
        }

        // 尝试获取更详细的元数据
        try {
            Object metadata = getTrackMetadataMethod.invoke(deviceManager, Integer.parseInt(selectedPlayer));
            if (metadata instanceof Map) {
                Map<?, ?> meta = (Map<?, ?>) metadata;
                return TrackInfo.builder()
                    .trackId(trackId)
                    .title((String) meta.get("title"))
                    .artist((String) meta.get("artist"))
                    .album((String) meta.get("album"))
                    .durationMs(meta.get("durationMs") instanceof Number ? ((Number) meta.get("durationMs")).longValue() : 0)
                    .build();
            }
        } catch (Exception e) {
            // 忽略，使用基础信息
        }

        return TrackInfo.builder()
            .trackId(trackId)
            .title((String) player.get("title"))
            .artist((String) player.get("artist"))
            .build();
    }

    @Override
    public int getSourcePlayerNumber() {
        try {
            return Integer.parseInt(selectedPlayer);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public void setSelectedPlayer(String playerNumber) {
        this.selectedPlayer = playerNumber;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSelectedPlayer() {
        if (deviceManager == null || getPlayersStateMethod == null) {
            return null;
        }

        try {
            Object result = getPlayersStateMethod.invoke(deviceManager);
            if (result instanceof Map) {
                Map<?, ?> state = (Map<?, ?>) result;
                Object players = state.get("players");
                if (players instanceof java.util.List) {
                    for (Object p : (java.util.List<?>) players) {
                        if (p instanceof Map) {
                            Map<?, ?> player = (Map<?, ?>) p;
                            Object num = player.get("number");
                            if (num != null && selectedPlayer.equals(num.toString())) {
                                return (Map<String, Object>) player;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DjLinkSourceInput] Error getting player state: " + e.getMessage());
        }
        return null;
    }
}
