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

        // 判断 STOPPED vs PAUSED
        Number beat = (Number) player.get("beat");
        Number timeMs = (Number) player.get("currentTimeMs");
        double nowSec = timeMs != null ? timeMs.doubleValue() / 1000.0 : 0;

        if (beat == null || beat.doubleValue() < 0 || nowSec <= 0.05) {
            return "STOPPED";
        }
        return "PAUSED";
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

        Number timeMs = (Number) player.get("currentTimeMs");
        return timeMs != null ? timeMs.doubleValue() / 1000.0 : 0.0;
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
