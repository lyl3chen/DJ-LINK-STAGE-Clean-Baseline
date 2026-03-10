package dbclient.ai;

import dbclient.config.UserSettingsStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boilerplate AI rule parser: natural language -> trigger rule payload.
 */
public class AiAgentService {
    // 这是 AI 规则翻译器：把“人话需求”先转成可执行的触发规则（当前为占位逻辑）。
    private static final AiAgentService INSTANCE = new AiAgentService();

    public static AiAgentService getInstance() { return INSTANCE; }

    public Map<String, Object> parseCommand(String prompt, Map<String, Object> trackMeta) {
        Map<String, Object> rule = new LinkedHashMap<>();
        String p = prompt == null ? "" : prompt.toLowerCase();
        String action = p.contains("drop") ? "COLOR_STROBE" : (p.contains("warm") ? "COLOR_WARM" : "COLOR_COOL");
        String when = p.contains("section") ? "SECTION_CHANGE" : "BEAT_1";
        rule.put("id", "rule-" + System.currentTimeMillis());
        rule.put("source", "ai-agent-boilerplate");
        rule.put("prompt", prompt);
        rule.put("when", when);
        rule.put("action", action);
        rule.put("trackMeta", trackMeta != null ? trackMeta : Map.of());
        return rule;
    }

    public Map<String, Object> createAndPersistRule(String prompt, Map<String, Object> trackMeta) {
        Map<String, Object> rule = parseCommand(prompt, trackMeta);
        UserSettingsStore.getInstance().addAiRule(rule);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rule", rule);
        return out;
    }
}
