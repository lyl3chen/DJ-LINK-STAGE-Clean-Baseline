package djlink.trigger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trigger Engine - detects events from player state changes
 * Uses deduplication to avoid repeated events
 */
public class TriggerEngine {
    
    private static TriggerEngine instance;
    
    // Previous state for comparison
    private final Map<Integer, PlayerState> previousStates = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHotCueFire = new ConcurrentHashMap<>();  // "player-cueIndex"
    private String previousMaster = null;
    private final Set<Integer> previousOnlinePlayers = Collections.synchronizedSet(new HashSet<>());
    
    // Event cooldown (ms)
    private static final long HOTCUE_COOLDOWN_MS = 5000;
    
    // Event listener callback for WebSocket push
    private static EventListener eventListener;
    
    public interface EventListener {
        void onEvent(Map<String, Object> event);
    }
    
    public static void setEventListener(EventListener listener) {
        eventListener = listener;
    }
    
    private TriggerEngine() {}
    
    public static TriggerEngine getInstance() {
        if (instance == null) {
            instance = new TriggerEngine();
        }
        return instance;
    }
    
    /**
     * Process current player states and generate events
     */
    public List<Map<String, Object>> processStates(List<Map<String, Object>> currentPlayers, String currentMaster) {
        List<Map<String, Object>> events = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        Set<Integer> currentOnlinePlayers = new HashSet<>();
        
        for (Map<String, Object> player : currentPlayers) {
            Integer playerNum = (Integer) player.get("number");
            if (playerNum == null) continue;
            
            currentOnlinePlayers.add(playerNum);
            PlayerState prev = previousStates.get(playerNum);
            
            // Player ONLINE event
            if (prev == null) {
                events.add(createEvent("PLAYER_ONLINE", playerNum, "player-" + playerNum, 
                    Map.of("reason", "first_seen")));
            }
            
            // Detect state changes
            events.addAll(detectTrackChanges(prev, player, playerNum));
            events.addAll(detectPlayStateChanges(prev, player, playerNum));
            events.addAll(detectSectionChanges(prev, player, playerNum));
            events.addAll(detectEnergyChanges(prev, player, playerNum));
            events.addAll(detectBarChanges(prev, player, playerNum));
            events.addAll(detectHotCueReached(prev, player, playerNum, now));
            
            // Save current state
            previousStates.put(playerNum, copyPlayerState(player));
        }
        
        // Detect player offline
        for (Integer prevPlayer : previousOnlinePlayers) {
            if (!currentOnlinePlayers.contains(prevPlayer)) {
                events.add(createEvent("PLAYER_OFFLINE", prevPlayer, "player-" + prevPlayer,
                    Map.of("reason", "no_longer_online")));
            }
        }
        
        // Detect master change
        if (previousMaster != null && currentMaster != null && !previousMaster.equals(currentMaster)) {
            events.add(createEvent("MASTER_CHANGED", null, null,
                Map.of("from", previousMaster, "to", currentMaster)));
        }
        previousMaster = currentMaster;
        previousOnlinePlayers.clear();
        previousOnlinePlayers.addAll(currentOnlinePlayers);
        
        // Notify listener about each event
        if (eventListener != null) {
            for (Map<String, Object> event : events) {
                eventListener.onEvent(event);
            }
        }
        
        return events;
    }
    
    private List<Map<String, Object>> detectTrackChanges(PlayerState prev, Map<String, Object> curr, int playerNum) {
        List<Map<String, Object>> events = new ArrayList<>();
        
        Map<String, Object> track = (Map<String, Object>) curr.get("track");
        String title = track != null ? (String) track.get("title") : null;
        String artist = track != null ? (String) track.get("artist") : null;
        Object dur = track != null ? track.get("duration") : null;
        String durationStr = dur != null ? dur.toString() : null;
        
        String currentKey = title + "|" + artist + "|" + durationStr;
        
        if (prev == null) {
            if (title != null) {
                events.add(createEvent("TRACK_LOADED", playerNum, "player-" + playerNum,
                    Map.of("title", title != null ? title : "unknown",
                           "artist", artist != null ? artist : "unknown")));
            }
        } else {
            String prevKey = prev.trackKey;
            
            if (prevKey == null && currentKey != null) {
                events.add(createEvent("TRACK_LOADED", playerNum, "player-" + playerNum,
                    Map.of("title", title != null ? title : "unknown",
                           "artist", artist != null ? artist : "unknown")));
            } else if (prevKey != null && !prevKey.equals(currentKey) && currentKey != null) {
                events.add(createEvent("TRACK_CHANGED", playerNum, "player-" + playerNum,
                    Map.of("title", title != null ? title : "unknown",
                           "artist", artist != null ? artist : "unknown")));
            }
        }
        
        return events;
    }
    
    private List<Map<String, Object>> detectPlayStateChanges(PlayerState prev, Map<String, Object> curr, int playerNum) {
        List<Map<String, Object>> events = new ArrayList<>();
        
        Boolean playing = (Boolean) curr.get("playing");
        boolean isPlaying = Boolean.TRUE.equals(playing);
        
        if (prev == null) {
            // First time seeing this player
        } else {
            boolean wasPlaying = prev.playing;
            
            if (!wasPlaying && isPlaying) {
                events.add(createEvent("PLAY_STARTED", playerNum, "player-" + playerNum, Map.of()));
            } else if (wasPlaying && !isPlaying) {
                events.add(createEvent("PLAY_STOPPED", playerNum, "player-" + playerNum, Map.of()));
            }
        }
        
        return events;
    }
    
    private List<Map<String, Object>> detectSectionChanges(PlayerState prev, Map<String, Object> curr, int playerNum) {
        List<Map<String, Object>> events = new ArrayList<>();
        
        Map<String, Object> cs = (Map<String, Object>) curr.get("currentSection");
        if (cs == null) return events;
        
        int currentIndex = (Integer) cs.get("index");
        String currentType = (String) cs.get("type");
        Double energy = cs.get("energy") != null ? ((Number) cs.get("energy")).doubleValue() : null;
        
        if (prev == null) {
            // First section seen
        } else {
            Integer prevIndex = prev.sectionIndex;
            String prevType = prev.sectionType;
            
            if (prevIndex != null && !prevIndex.equals(currentIndex)) {
                events.add(createEvent("SECTION_CHANGED", playerNum, "player-" + playerNum,
                    Map.of("fromIndex", prevIndex, "toIndex", currentIndex,
                           "fromType", prevType != null ? prevType : "UNKNOWN",
                           "toType", currentType != null ? currentType : "UNKNOWN",
                           "energy", energy != null ? energy : 0.0)));
            } else if (prevType != null && !prevType.equals(currentType)) {
                events.add(createEvent("SECTION_CHANGED", playerNum, "player-" + playerNum,
                    Map.of("fromType", prevType, "toType", currentType,
                           "energy", energy != null ? energy : 0.0)));
            }
        }
        
        return events;
    }
    
    private List<Map<String, Object>> detectEnergyChanges(PlayerState prev, Map<String, Object> curr, int playerNum) {
        List<Map<String, Object>> events = new ArrayList<>();
        
        Map<String, Object> cs = (Map<String, Object>) curr.get("currentSection");
        Map<String, Object> decision = (Map<String, Object>) curr.get("decision");
        
        if (cs == null || decision == null) return events;
        
        Double energy = cs.get("energy") != null ? ((Number) cs.get("energy")).doubleValue() : null;
        String currentLevel = getEnergyLevel(energy);
        
        if (prev == null) {
            // First energy seen
        } else {
            String prevLevel = prev.energyLevel;
            
            if (prevLevel != null && !prevLevel.equals(currentLevel)) {
                events.add(createEvent("ENERGY_LEVEL_CHANGED", playerNum, "player-" + playerNum,
                    Map.of("from", prevLevel, "to", currentLevel,
                           "energy", energy != null ? energy : 0.0)));
            }
        }
        
        return events;
    }
    
    /**
     * Detect bar changes - when beatWithinBar goes from 4 to 1
     */
    private List<Map<String, Object>> detectBarChanges(PlayerState prev, Map<String, Object> curr, int playerNum) {
        List<Map<String, Object>> events = new ArrayList<>();
        
        // Get current beat info
        Object beatObj = curr.get("beat");
        int currentBeat = beatObj != null ? ((Number) beatObj).intValue() : 0;
        
        // Calculate current beatWithinBar and barIndex
        int beatWithinBar = (currentBeat - 1) % 4 + 1;
        int barIndex = (currentBeat - 1) / 4 + 1;
        
        if (prev == null) {
            // First time seeing this player
        } else {
            // Detect bar change: previous beatWithinBar was 4, now is 1
            if (prev.beatWithinBar == 4 && beatWithinBar == 1) {
                events.add(createEvent("BAR_CHANGED", playerNum, "player-" + playerNum,
                    Map.of("bar", barIndex, "beat", beatWithinBar)));
            }
        }
        
        return events;
    }
    /**
     * Detect when playback reaches a hot cue (±200ms window)
     */
    private List<Map<String, Object>> detectHotCueReached(PlayerState prev, Map<String, Object> curr, int playerNum, long now) {
        List<Map<String, Object>> events = new ArrayList<>();
        
        // Only process if playing
        Boolean playing = (Boolean) curr.get("playing");
        if (!Boolean.TRUE.equals(playing)) return events;
        
        // Get current playback time - prefer beatgrid time if available
        Object beatObj = curr.get("beat");
        Object bpmObj = curr.get("bpm");
        Object beatTimeMsObj = curr.get("beatTimeMs");
        
        if (beatObj == null) return events;
        
        double currentTimeMs;
        String timeSource;
        
        // Use beatgrid time if available
        if (beatTimeMsObj != null) {
            currentTimeMs = ((Number) beatTimeMsObj).doubleValue();
            timeSource = "beatgrid";
        } else if (bpmObj != null) {
            // Fallback to estimated
            int currentBeat = ((Number) beatObj).intValue();
            double bpm = ((Number) bpmObj).doubleValue();
            currentTimeMs = currentBeat * 60000.0 / bpm;
            timeSource = "estimated";
        } else {
            return events;
        }
        
        // Get hot cue times from analysis
        Map<String, Object> analysis = (Map<String, Object>) curr.get("analysis");
        if (analysis == null) return events;
        
        List<Integer> hotCueTimes = (List<Integer>) analysis.get("hotCueTimesMs");
        if (hotCueTimes == null || hotCueTimes.isEmpty()) return events;
        
        // Get current track key
        Map<String, Object> track = (Map<String, Object>) curr.get("track");
        String currentTrackKey = null;
        if (track != null) {
            String title = (String) track.get("title");
            String artist = (String) track.get("artist");
            Object dur = track.get("duration");
            String durationStr = dur != null ? dur.toString() : null;
            currentTrackKey = title + "|" + artist + "|" + durationStr;
        }
        
        // Check each hot cue
        for (int i = 0; i < hotCueTimes.size(); i++) {
            Integer cueTimeMs = hotCueTimes.get(i);
            if (cueTimeMs == null) continue;
            
            double deltaMs = currentTimeMs - cueTimeMs;
            
            // Check if within window (±200ms)
            if (Math.abs(deltaMs) <= 200) {
                // Check cooldown: 5 seconds
                String cooldownKey = playerNum + "-" + i + "-" + currentTrackKey;
                Long lastFire = lastHotCueFire.get(cooldownKey);
                
                if (lastFire == null || (now - lastFire) > HOTCUE_COOLDOWN_MS) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("cueIndex", i + 1);
                    data.put("cueTimeMs", cueTimeMs);
                    data.put("currentTimeMs", (int) currentTimeMs);
                    data.put("deltaMs", (int) deltaMs);
                    data.put("timeSource", timeSource);
                    
                    events.add(createEvent("HOTCUE_REACHED", playerNum, "player-" + playerNum, data));
                    lastHotCueFire.put(cooldownKey, now);
                }
            }
        }
        
        return events;
    }
    private String getEnergyLevel(Double energy) {
        if (energy == null) return "UNKNOWN";
        if (energy < 0.3) return "LOW";
        if (energy < 0.6) return "MEDIUM";
        return "HIGH";
    }
    
    private Map<String, Object> createEvent(String type, Integer player, String triggerKey, Map<String, Object> data) {
        Map<String, Object> event = new HashMap<>();
        event.put("ts", System.currentTimeMillis() / 1000);
        event.put("type", type);
        if (player != null) event.put("player", player);
        if (triggerKey != null) event.put("triggerKey", triggerKey);
        event.put("data", data);
        return event;
    }
    
    private PlayerState copyPlayerState(Map<String, Object> p) {
        PlayerState s = new PlayerState();
        s.playing = Boolean.TRUE.equals(p.get("playing"));
        
        // Copy beat info for bar detection
        Object beatObj = p.get("beat");
        if (beatObj != null) {
            s.currentBeat = ((Number) beatObj).intValue();
            s.beatWithinBar = (s.currentBeat - 1) % 4 + 1;
            s.barIndex = (s.currentBeat - 1) / 4 + 1;
        }
        
        Map<String, Object> track = (Map<String, Object>) p.get("track");
        if (track != null) {
            String title = (String) track.get("title");
            String artist = (String) track.get("artist");
            Object dur = track.get("duration");
            String durationStr = dur != null ? dur.toString() : null;
            s.trackKey = title + "|" + artist + "|" + durationStr;
        }
        
        Map<String, Object> cs = (Map<String, Object>) p.get("currentSection");
        if (cs != null) {
            s.sectionIndex = (Integer) cs.get("index");
            s.sectionType = (String) cs.get("type");
            Object energy = cs.get("energy");
            if (energy != null) {
                s.energyLevel = getEnergyLevel(((Number) energy).doubleValue());
            }
        }
        
        return s;
    }
    
    /**
     * Simple state holder
     */
    static class PlayerState {
        boolean playing;
        String trackKey;
        Integer sectionIndex;
        String sectionType;
        String energyLevel;
        int beatWithinBar;  // 1-4
        int barIndex;       // calculated bar number
        int currentBeat;     // absolute beat number
    }
}
