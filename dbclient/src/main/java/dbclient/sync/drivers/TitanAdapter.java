package dbclient.sync.drivers;

import com.google.gson.*;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Titan HTTP 适配器：
 * - v11+：扫描 /titan/handles -> BPMMaster:X -> titanId，再调 script/2/Masters/SetMaster
 * - v10 ：直接调 script/Masters/SetMasterLevel (RollerA + index)
 */
public class TitanAdapter {
    private static final Gson GSON = new Gson();

    private String baseUrl;
    private String versionMode = "auto"; // auto | v10 | v11
    private int detectedVersion = 0;      // 10 / 11
    private final Map<Integer, Integer> bpmMasterTitanId = new HashMap<>();

    public synchronized void configure(String baseUrl, String versionMode) {
        this.baseUrl = normalizeBase(baseUrl);
        this.versionMode = versionMode == null ? "auto" : versionMode.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized String getBaseUrl() { return baseUrl; }
    public synchronized int getDetectedVersion() { return detectedVersion; }
    public synchronized Map<Integer, Integer> getBpmMasterTitanId() { return new LinkedHashMap<>(bpmMasterTitanId); }

    public synchronized void connectAndScan() throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("Titan baseUrl is empty");
        int v = detectVersion();
        detectedVersion = v;
        bpmMasterTitanId.clear();
        if (v >= 11) {
            scanHandlesV11();
        }
    }

    public synchronized int setBpm(int masterIndex, double bpm) throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException("Titan not configured");
        if (masterIndex < 0 || masterIndex > 3) throw new IllegalArgumentException("masterIndex must be 0..3");

        // 关键要求：发给 Titan 的 BPM 必须是整数。
        int bpmInt = (int) Math.round(bpm);
        bpmInt = Math.max(40, Math.min(240, bpmInt));

        int v = detectedVersion > 0 ? detectedVersion : detectVersion();
        detectedVersion = v;
        if (v >= 11) {
            Integer titanId = bpmMasterTitanId.get(masterIndex);
            if (titanId == null) {
                scanHandlesV11();
                titanId = bpmMasterTitanId.get(masterIndex);
            }
            if (titanId == null) throw new IllegalStateException("BPMMaster:" + masterIndex + " titanId not found");
            String q = "handle_titanId=" + titanId + "&value=" + bpmInt;
            return httpGetCode(baseUrl + "/titan/script/2/Masters/SetMaster?" + q);
        } else {
            String q = "string=RollerA&int=" + masterIndex + "&level=" + bpmInt + "&float=1.0&bool=false";
            return httpGetCode(baseUrl + "/titan/script/Masters/SetMasterLevel?" + q);
        }
    }

    private int detectVersion() {
        if ("v10".equals(versionMode)) return 10;
        if ("v11".equals(versionMode)) return 11;

        try {
            String txt = httpGetText(baseUrl + "/titan/handles");
            JsonElement root = JsonParser.parseString(txt);
            List<HandleInfo> all = new ArrayList<>();
            flattenHandles(root, all);
            for (HandleInfo h : all) {
                if ("rateMasterHandle".equalsIgnoreCase(h.type) && h.masterKey != null && h.masterKey.startsWith("BPMMaster:")) {
                    return 11;
                }
            }
        } catch (Exception ignored) {}
        return 10;
    }

    private void scanHandlesV11() throws Exception {
        String txt = httpGetText(baseUrl + "/titan/handles");
        JsonElement root = JsonParser.parseString(txt);
        List<HandleInfo> all = new ArrayList<>();
        flattenHandles(root, all);
        for (HandleInfo h : all) {
            if (!"rateMasterHandle".equalsIgnoreCase(h.type)) continue;
            if (h.masterKey == null || !h.masterKey.startsWith("BPMMaster:")) continue;
            Integer idx = parseMasterIndex(h.masterKey);
            if (idx == null || idx < 0 || idx > 3) continue;
            if (h.titanId != null) bpmMasterTitanId.put(idx, h.titanId);
        }
    }

    private static Integer parseMasterIndex(String key) {
        try {
            int p = key.indexOf(':');
            if (p < 0) return null;
            return Integer.parseInt(key.substring(p + 1).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static class HandleInfo {
        Integer titanId;
        String type;
        String masterKey;
    }

    private static void flattenHandles(JsonElement el, List<HandleInfo> out) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray()) {
            for (JsonElement c : el.getAsJsonArray()) flattenHandles(c, out);
            return;
        }
        if (!el.isJsonObject()) return;

        JsonObject o = el.getAsJsonObject();
        HandleInfo hi = new HandleInfo();

        if (o.has("titanId") && o.get("titanId").isJsonPrimitive()) {
            try { hi.titanId = o.get("titanId").getAsInt(); } catch (Exception ignored) {}
        }
        if (o.has("type") && o.get("type").isJsonPrimitive()) {
            hi.type = o.get("type").getAsString();
        }
        if (o.has("properties") && o.get("properties").isJsonArray()) {
            for (JsonElement pe : o.getAsJsonArray("properties")) {
                if (!pe.isJsonObject()) continue;
                JsonObject po = pe.getAsJsonObject();
                String k = po.has("key") ? po.get("key").getAsString() : null;
                String v = po.has("value") ? po.get("value").getAsString() : null;
                if ("Master".equalsIgnoreCase(k)) hi.masterKey = v;
            }
        }

        if (hi.titanId != null || hi.type != null || hi.masterKey != null) out.add(hi);

        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            flattenHandles(e.getValue(), out);
        }
    }

    private static String normalizeBase(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String httpGetText(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(1500);
        c.setReadTimeout(2000);
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        byte[] bytes = is == null ? new byte[0] : is.readAllBytes();
        String txt = new String(bytes, StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code + " " + txt);
        return txt;
    }

    private static int httpGetCode(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(1200);
        c.setReadTimeout(1500);
        return c.getResponseCode();
    }
}
