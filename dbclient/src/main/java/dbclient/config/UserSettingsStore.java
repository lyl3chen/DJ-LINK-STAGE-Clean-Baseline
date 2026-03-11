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
                        "quantum", 4,
                        "bridgeHost", "127.0.0.1",
                        "bridgeSendPort", 19110,
                        "bridgeListenPort", 19111
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
