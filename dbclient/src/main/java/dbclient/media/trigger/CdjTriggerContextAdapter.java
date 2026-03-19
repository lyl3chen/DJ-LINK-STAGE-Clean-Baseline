package dbclient.media.trigger;

import dbclient.media.model.*;

/**
 * CDJ / beat-link 触发上下文适配器
 * 
 * 数据来源：
 * - 通过反射调用 djlink.DeviceManager 获取 CDJ 状态
 * 
 * 可用字段映射：
 * - source: CDJ
 * - trackId/title/artist/durationMs: 从 track metadata 获取
 * - playbackState/positionMs: 从 CdjStatus 获取
 * - bpm: 从 CdjStatus 获取（实时 BPM）
 * - beatNumber/measureNumber: 从 CdjStatus 获取
 * - phase: 从 CdjStatus 获取
 * - markers: 暂不支持（CDJ 模式下由外部设备管理）
 */
public class CdjTriggerContextAdapter implements TriggerContextAdapter {

    private Object deviceManager;
    private boolean initialized = false;
    private int playerNumber = 1;  // 默认读取 Player 1

    public CdjTriggerContextAdapter() {
        // 通过反射初始化
        try {
            Class<?> dmClass = Class.forName("djlink.DeviceManager");
            this.deviceManager = dmClass.getMethod("getInstance").invoke(null);
            this.initialized = true;
            System.out.println("[CdjTriggerContextAdapter] Initialized, using Player " + playerNumber);
        } catch (Exception e) {
            System.err.println("[CdjTriggerContextAdapter] Failed to initialize: " + e.getMessage());
            this.initialized = false;
        }
    }

    @Override
    public TriggerContext buildContext() {
        if (!isAvailable()) {
            return null;
        }

        TriggerContext.Builder builder = TriggerContext.builder()
            .source(TriggerSource.CDJ)
            .timestamp(System.currentTimeMillis());

        try {
            // 获取 CDJ 播放状态
            Class<?> dmClass = deviceManager.getClass();
            
            // 获取 players state
            Object playersState = dmClass.getMethod("getPlayersState").invoke(deviceManager);
            if (playersState != null) {
                // 尝试获取当前 player 状态
                Object playerState = getPlayerState(playersState, playerNumber);
                if (playerState != null) {
                    mapPlayerState(builder, playerState);
                }
            }

        } catch (Exception e) {
            System.err.println("[CdjTriggerContextAdapter] Error building context: " + e.getMessage());
        }

        return builder.build();
    }

    /**
     * 从 players state 中获取指定 player 的状态
     */
    private Object getPlayerState(Object playersState, int playerNumber) {
        try {
            // 尝试 getPlayer(int) 方法
            java.lang.reflect.Method m = playersState.getClass().getMethod("getPlayer", int.class);
            return m.invoke(playersState, playerNumber);
        } catch (Exception e) {
            // 尝试直接获取 players 数组/列表
            try {
                java.lang.reflect.Method m = playersState.getClass().getMethod("getPlayers");
                Object players = m.invoke(playersState);
                if (players instanceof Object[]) {
                    Object[] arr = (Object[]) players;
                    if (arr.length >= playerNumber) {
                        return arr[playerNumber - 1];
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 将 CDJ 状态映射到 TriggerContext
     */
    private void mapPlayerState(TriggerContext.Builder builder, Object playerState) {
        try {
            // 播放状态
            Object stateObj = getMethodValue(playerState, "getState");
            if (stateObj != null) {
                String stateStr = stateObj.toString();
                PlaybackStatus.State state = mapPlaybackState(stateStr);
                builder.playbackState(state);
            }

            // 位置（毫秒）
            Object posObj = getMethodValue(playerState, "getPosition");
            if (posObj instanceof Number) {
                builder.positionMs(((Number) posObj).longValue());
            }

            // BPM
            Object bpmObj = getMethodValue(playerState, "getEffectiveBpm");
            if (bpmObj instanceof Number) {
                builder.bpm(((Number) bpmObj).intValue());
            }

            // Phase
            Object phaseObj = getMethodValue(playerState, "getPhase");
            if (phaseObj instanceof Number) {
                builder.phase(((Number) phaseObj).doubleValue());
            }

            // Beat Number
            Object beatObj = getMethodValue(playerState, "getCurrentBeat");
            if (beatObj instanceof Number) {
                builder.beatNumber(((Number) beatObj).intValue());
            }

            // Measure Number
            Object measureObj = getMethodValue(playerState, "getCurrentMeasure");
            if (measureObj instanceof Number) {
                builder.measureNumber(((Number) measureObj).intValue());
            }

            // 曲目信息
            Object trackObj = getMethodValue(playerState, "getTrack");
            if (trackObj != null) {
                builder.trackId(getStringValue(trackObj, "getTrackId"));
                builder.title(getStringValue(trackObj, "getTitle"));
                builder.artist(getStringValue(trackObj, "getArtist"));
                
                Object durObj = getMethodValue(trackObj, "getDuration");
                if (durObj instanceof Number) {
                    builder.durationMs(((Number) durObj).longValue());
                }
            }

        } catch (Exception e) {
            System.err.println("[CdjTriggerContextAdapter] Error mapping player state: " + e.getMessage());
        }
    }

    /**
     * 通过反射获取方法返回值
     */
    private Object getMethodValue(Object obj, String methodName) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
            return m.invoke(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 通过反射获取字符串值
     */
    private String getStringValue(Object obj, String methodName) {
        Object val = getMethodValue(obj, methodName);
        return val != null ? val.toString() : null;
    }

    /**
     * 映射播放状态
     */
    private PlaybackStatus.State mapPlaybackState(String stateStr) {
        if (stateStr == null) return PlaybackStatus.State.STOPPED;
        
        String s = stateStr.toUpperCase();
        if (s.contains("PLAYING")) return PlaybackStatus.State.PLAYING;
        if (s.contains("PAUSED")) return PlaybackStatus.State.PAUSED;
        return PlaybackStatus.State.STOPPED;
    }

    @Override
    public TriggerSource getSource() {
        return TriggerSource.CDJ;
    }

    @Override
    public boolean isAvailable() {
        return initialized && deviceManager != null;
    }

    /**
     * 设置读取的 Player 编号
     */
    public void setPlayerNumber(int playerNumber) {
        this.playerNumber = playerNumber;
    }
}
