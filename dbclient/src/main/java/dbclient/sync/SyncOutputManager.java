package dbclient.sync;

import dbclient.config.UserSettingsStore;
import dbclient.sync.drivers.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signal output scheduler (LTC/MTC/Link/Console API) with unified driver lifecycle.
 */
public class SyncOutputManager {
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
    public synchronized void applySettings() {
        Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
        Map<String, Object> sync = (Map<String, Object>) settings.getOrDefault("sync", Map.of());
        for (Map.Entry<String, OutputDriver> e : drivers.entrySet()) {
            Map<String, Object> cfg = sync.get(e.getKey()) instanceof Map ? (Map<String, Object>) sync.get(e.getKey()) : Map.of();
            boolean enabled = Boolean.TRUE.equals(cfg.get("enabled"));
            // 配置保存后强制重启驱动，确保 deviceName/fps/gain 等修改立即生效。
            e.getValue().stop();
            if (enabled) e.getValue().start(cfg);
        }
    }

    public synchronized void onSemanticEvent(String event, Integer player, Map<String, Object> data) {
        lastSemantic.put("event", event);
        lastSemantic.put("player", player);
        lastSemantic.put("data", data != null ? data : Map.of());
        broadcastState(buildDerivedState());
    }

    @SuppressWarnings("unchecked")
    public synchronized void onPlayersState(Map<String, Object> playersState) {
        Map<String, Object> derived = buildDerivedState();
        if (playersState != null) {
            Object players = playersState.get("players");
            if (players instanceof List) {
                List<?> list = (List<?>) players;
                Map<String, Object> chosen = null;

                Map<String, Object> settings = UserSettingsStore.getInstance().getAll();
                Map<String, Object> sync = settings.get("sync") instanceof Map ? (Map<String, Object>) settings.get("sync") : Map.of();
                String sourceMode = String.valueOf(sync.getOrDefault("sourceMode", "master"));
                int selectedPlayer = sync.get("masterPlayer") instanceof Number ? ((Number) sync.get("masterPlayer")).intValue() : 1;

                // A) 手动模式：优先使用指定 player（可播放或暂停都跟随它的时间）
                if ("manual".equalsIgnoreCase(sourceMode)) {
                    for (Object o : list) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> p = (Map<String, Object>) o;
                        int num = p.get("number") instanceof Number ? ((Number) p.get("number")).intValue() : -1;
                        if (num == selectedPlayer && Boolean.TRUE.equals(p.get("active"))) {
                            chosen = p; break;
                        }
                    }
                }

                // B) 跟随 master（默认）
                if (chosen == null) {
                    for (Object o : list) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> p = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(p.get("master")) && Boolean.TRUE.equals(p.get("active"))) {
                            chosen = p; break;
                        }
                    }
                }

                // C) 兜底：任何在播放的 deck
                if (chosen == null) {
                    for (Object o : list) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> p = (Map<String, Object>) o;
                        if (Boolean.TRUE.equals(p.get("playing")) && Boolean.TRUE.equals(p.get("active"))) { chosen = p; break; }
                    }
                }

                if (chosen != null) {
                    Object ms = chosen.get("currentTimeMs");
                    if (!(ms instanceof Number)) ms = chosen.get("beatTimeMs");
                    Object bpm = chosen.get("bpm");
                    if (ms instanceof Number) {
                        double sec = ((Number) ms).doubleValue()/1000.0;
                        derived.put("masterTimeSec", sec);
                    }
                    if (bpm instanceof Number) derived.put("masterBpm", ((Number) bpm).doubleValue());
                    derived.put("sourcePlayer", chosen.get("number"));
                    derived.put("sourceMode", sourceMode);
                    boolean playing = Boolean.TRUE.equals(chosen.get("playing"));
                    boolean active = !Boolean.FALSE.equals(chosen.get("active"));
                    derived.put("sourcePlaying", playing);
                    derived.put("sourceActive", active);
                    double nowSec = ms instanceof Number ? ((Number) ms).doubleValue()/1000.0 : 0.0;
                    String sourceState = !active ? "OFFLINE" : (playing ? "PLAYING" : (nowSec > 0.05 ? "PAUSED" : "STOPPED"));
                    derived.put("sourceState", sourceState);
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
        state.put("sourcePlaying", false);
        state.put("sourceActive", false);
        state.put("sourceState", "OFFLINE");
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
