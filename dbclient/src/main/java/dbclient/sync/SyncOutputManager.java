package dbclient.sync;

import dbclient.config.UserSettingsStore;
import dbclient.sync.drivers.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signal output scheduler (LTC/MTC/Link/Console API) with unified driver lifecycle.
 */
public class SyncOutputManager {
    // 这是输出调度总控：把语义事件和主时钟分发给 LTC/MTC/Link 等驱动。
    private static final SyncOutputManager INSTANCE = new SyncOutputManager();
    private final Map<String, OutputDriver> drivers = new LinkedHashMap<>();
    private final Map<String, Object> lastSemantic = new ConcurrentHashMap<>();

    public static SyncOutputManager getInstance() { return INSTANCE; }

    private SyncOutputManager() {
        register(new LtcDriver());
        register(new MtcDriver());
        register(new AbletonLinkDriver());
        register(new ConsoleApiDriver());
    }

    private void register(OutputDriver d) { drivers.put(d.name(), d); }

    @SuppressWarnings("unchecked")
    // 读取用户配置并启动/停止各驱动（比如勾选了 LTC 就启动 LTC 驱动）。
    public synchronized void applySettings() {
        Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
        Map<String, Object> sync = (Map<String, Object>) settings.getOrDefault("sync", Map.of());
        for (Map.Entry<String, OutputDriver> e : drivers.entrySet()) {
            Map<String, Object> cfg = sync.get(e.getKey()) instanceof Map ? (Map<String, Object>) sync.get(e.getKey()) : Map.of();
            boolean enabled = Boolean.TRUE.equals(cfg.get("enabled"));
            if (enabled) e.getValue().start(cfg);
            else e.getValue().stop();
        }
    }

    public synchronized void onSemanticEvent(String event, Integer player, Map<String, Object> data) {
        lastSemantic.put("event", event);
        lastSemantic.put("player", player);
        lastSemantic.put("data", data != null ? data : Map.of());
        broadcastState(buildDerivedState());
    }

    public synchronized void onPlayersState(Map<String, Object> playersState) {
        Map<String, Object> derived = buildDerivedState();
        if (playersState != null) {
            Object players = playersState.get("players");
            if (players instanceof List) {
                List<?> list = (List<?>) players;
                if (!list.isEmpty() && list.get(0) instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> first = (Map<String, Object>) list.get(0);
                    Object ms = first.get("currentTimeMs");
                    Object bpm = first.get("bpm");
                    if (ms instanceof Number) derived.put("masterTimeSec", ((Number) ms).doubleValue()/1000.0);
                    if (bpm instanceof Number) derived.put("masterBpm", ((Number) bpm).doubleValue());
                }
            }
        }
        broadcastState(derived);
    }

    private void broadcastState(Map<String, Object> state) {
        for (OutputDriver d : drivers.values()) d.update(state);
    }

    private Map<String, Object> buildDerivedState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("masterTimeSec", 0.0);
        state.put("masterBpm", 120.0);
        state.put("semantic", new LinkedHashMap<>(lastSemantic));
        return state;
    }

    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> ds = new LinkedHashMap<>();
        for (OutputDriver d : drivers.values()) ds.put(d.name(), d.status());
        out.put("drivers", ds);
        out.put("semantic", new LinkedHashMap<>(lastSemantic));
        return out;
    }
}
