package dbclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Persistent settings for modular services and UI.
 */
public class UserSettingsStore {
    // 这是“设置管家”：负责把配置读写到 config/user_settings.json。
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();
    private static final Path SETTINGS_PATH = Paths.get("..", "config", "user_settings.json");

    private static final UserSettingsStore INSTANCE = new UserSettingsStore();
    private final Object lock = new Object();
    private Map<String, Object> cache;

    public static UserSettingsStore getInstance() {
        return INSTANCE;
    }

    public Map<String, Object> getAll() {
        synchronized (lock) {
            ensureLoaded();
            return deepCopy(cache);
        }
    }

    public void replaceAll(Map<String, Object> data) {
        synchronized (lock) {
            ensureLoaded();
            cache = deepCopy(data);
            ensureDefaults(cache);
            save();
        }
    }

    public void patch(Map<String, Object> patch) {
        synchronized (lock) {
            ensureLoaded();
            merge(cache, patch);
            ensureDefaults(cache);
            save();
        }
    }

    public List<Map<String, Object>> getAiRules() {
        synchronized (lock) {
            ensureLoaded();
            Object rules = cache.get("aiRules");
            if (rules instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) rules;
                return list;
            }
            return new ArrayList<>();
        }
    }

    public void addAiRule(Map<String, Object> rule) {
        synchronized (lock) {
            ensureLoaded();
            List<Map<String, Object>> rules = getAiRules();
            rules.add(rule);
            cache.put("aiRules", rules);
            save();
        }
    }

    private void ensureLoaded() {
        if (cache != null) return;
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            if (Files.exists(SETTINGS_PATH)) {
                String txt = Files.readString(SETTINGS_PATH, StandardCharsets.UTF_8);
                Map<String, Object> parsed = GSON.fromJson(txt, MAP_TYPE);
                cache = parsed != null ? parsed : new LinkedHashMap<>();
            } else {
                cache = defaultSettings();
                save();
            }
            ensureDefaults(cache);
        } catch (IOException e) {
            cache = defaultSettings();
        }
    }

    private void save() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Files.writeString(SETTINGS_PATH, GSON.toJson(cache), StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private static Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ui", new LinkedHashMap<>(Map.of(
                "tab", "dashboard",
                "darkMode", true
        )));
        m.put("sync", new LinkedHashMap<>(Map.of(
                "enabled", true,
                "sourceMode", "master",
                "masterPlayer", 1,
                "ltc", new LinkedHashMap<>(Map.of(
                        "enabled", false,
                        "fps", 25,
                        "start", "00:00:00:00",
                        "sampleRate", 48000,
                        "gainDb", -8,
                        "deviceName", "default"
                )),
                "mtc", new LinkedHashMap<>(Map.of("enabled", false, "midiPort", "")),
                "abletonLink", new LinkedHashMap<>(Map.of(
                        "enabled", false,
                        "port", 17000,
                        "updateIntervalMs", 20
                )),
                "titanApi", new LinkedHashMap<>(Map.of(
                        "enabled", false,
                        "ip", "127.0.0.1",
                        "versionMode", "auto",
                        "masterIndices", java.util.List.of(0),
                        "rateLimitMs", 500
                )),
                "ma2Telnet", new LinkedHashMap<>(Map.ofEntries(
                        Map.entry("enabled", false),
                        Map.entry("host", "127.0.0.1"),
                        Map.entry("port", 30000),
                        Map.entry("user", "remote"),
                        Map.entry("pass", "1234"),
                        Map.entry("rateLimitMs", 500),
                        Map.entry("minBpm", 40),
                        Map.entry("maxBpm", 240),
                        Map.entry("integerOnly", true),
                        Map.entry("onlyWhenPlaying", true),
                        Map.entry("speedMasterIndex", 1),
                        Map.entry("commandTemplate", "SpecialMaster 3.{index} At {bpm}")
                ))
        )));
        m.put("ai", new LinkedHashMap<>(Map.of(
                "api_key", "",
                "model", "",
                "enabled", false
        )));
        m.put("aiRules", new ArrayList<>());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void ensureDefaults(Map<String, Object> map) {
        Map<String, Object> def = defaultSettings();
        mergeMissing(map, def);

        // 清理旧自研 bridge 残留配置，统一切换到 lib-carabiner 配置语义。
        Object syncObj = map.get("sync");
        if (syncObj instanceof Map) {
            Map<String, Object> sync = (Map<String, Object>) syncObj;
            Object linkObj = sync.get("abletonLink");
            if (linkObj instanceof Map) {
                Map<String, Object> link = (Map<String, Object>) linkObj;
                link.remove("bridgeHost");
                link.remove("bridgeSendPort");
                link.remove("bridgeListenPort");
                link.remove("quantum");
            }

            // Titan 配置兼容迁移：旧 baseUrl -> 新 ip（端口固定 4430）。
            Object titanObj = sync.get("titanApi");
            if (titanObj instanceof Map) {
                Map<String, Object> titan = (Map<String, Object>) titanObj;
                if (!titan.containsKey("ip") && titan.get("baseUrl") != null) {
                    String raw = String.valueOf(titan.get("baseUrl"));
                    try {
                        String s = raw.trim();
                        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
                        java.net.URI u = java.net.URI.create(s);
                        if (u.getHost() != null && !u.getHost().isBlank()) titan.put("ip", u.getHost());
                    } catch (Exception ignored) {}
                }
                titan.remove("baseUrl");
                if (!titan.containsKey("masterIndices") && titan.get("masterIndex") instanceof Number) {
                    int mi = ((Number) titan.get("masterIndex")).intValue();
                    titan.put("masterIndices", java.util.List.of(Math.max(0, Math.min(3, mi))));
                }
                titan.remove("masterIndex");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeMissing(Map<String, Object> target, Map<String, Object> defaults) {
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            if (!target.containsKey(e.getKey())) {
                target.put(e.getKey(), e.getValue());
                continue;
            }
            if (e.getValue() instanceof Map && target.get(e.getKey()) instanceof Map) {
                mergeMissing((Map<String, Object>) target.get(e.getKey()), (Map<String, Object>) e.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (e.getValue() instanceof Map && target.get(e.getKey()) instanceof Map) {
                merge((Map<String, Object>) target.get(e.getKey()), (Map<String, Object>) e.getValue());
            } else {
                target.put(e.getKey(), e.getValue());
            }
        }
    }

    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        return GSON.fromJson(GSON.toJson(src), MAP_TYPE);
    }
}
