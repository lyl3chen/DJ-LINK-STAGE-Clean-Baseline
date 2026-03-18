package dbclient.config;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class UserSettingsStoreConfigTest {

    @Test
    public void shouldPatchAndReadSourceAndDeviceConfigs() {
        UserSettingsStore store = UserSettingsStore.getInstance();
        Map<String, Object> backup = store.getAll();

        try {
            Map<String, Object> patch = new LinkedHashMap<>();
            Map<String, Object> sync = new LinkedHashMap<>();
            sync.put("sourceMode", "manual");
            sync.put("masterPlayer", 2);

            Map<String, Object> localPlayer = new LinkedHashMap<>();
            localPlayer.put("audioDevice", "Device [plughw:3,0]");
            sync.put("localPlayer", localPlayer);

            Map<String, Object> ltc = new LinkedHashMap<>();
            ltc.put("deviceName", "PCH [plughw:0,0]");
            sync.put("ltc", ltc);

            patch.put("sync", sync);
            store.patch(patch);

            Map<String, Object> all = store.getAll();
            Map<String, Object> gotSync = asMap(all.get("sync"));
            assertEquals("manual", String.valueOf(gotSync.get("sourceMode")));
            assertEquals(2, ((Number) gotSync.get("masterPlayer")).intValue());

            Map<String, Object> gotLocal = asMap(gotSync.get("localPlayer"));
            assertEquals("Device [plughw:3,0]", String.valueOf(gotLocal.get("audioDevice")));

            Map<String, Object> gotLtc = asMap(gotSync.get("ltc"));
            assertEquals("PCH [plughw:0,0]", String.valueOf(gotLtc.get("deviceName")));

        } finally {
            // restore original settings to avoid side effects
            store.replaceAll(backup);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
