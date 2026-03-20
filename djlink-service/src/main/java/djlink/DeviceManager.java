package djlink;

import djlink.trigger.TriggerEngine;
import org.deepsymmetry.beatlink.*;
import org.deepsymmetry.beatlink.data.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Device Manager using beat-link
 * Provides: online devices, player status, BPM, track metadata
 */
public class DeviceManager {
    
    private static DeviceManager instance;
    
    // Device state
    private final Map<Integer, PlayerState> players = new ConcurrentHashMap<>();
    private final AtomicReference<String> masterPlayer = new AtomicReference<>(null);  // 真实 master（来自 MasterListener）
    private final AtomicReference<String> activeBeatSource = new AtomicReference<>(null);  // 当前发 beat 的播放器
    private final AtomicInteger bpm = new AtomicInteger(0);
    private static final long PLAYER_STALE_MS = 15000;
    private static final long SCAN_AUTO_STOP_MS = 30000;
    private volatile boolean running = false;
    private volatile boolean listenersAdded = false;
    private volatile long lastSignalMs = System.currentTimeMillis();
    
    // beat-link components
    private DeviceFinder deviceFinder;
    private VirtualCdj virtualCdj;
    private BeatFinder beatFinder;
    private MetadataFinder metadataFinder;
    private BeatGridFinder beatGridFinder;
    private WaveformFinder waveformFinder;
    private AnalysisTagFinder analysisTagFinder;
    private ArtFinder artFinder;

    // 轻量预热缓存层：用于提前拉取并缓存当前曲目 metadata，降低播放瞬间 miss。
    private final MetadataWarmupService metadataWarmup = new MetadataWarmupService();
    // 最近一次曲目元数据查询结果（用于前端显示实时 HIT/MISS）
    private volatile String metadataLastLookup = "UNKNOWN";
    private volatile long metadataLastLookupTs = 0L;
    
    private DeviceManager() {}
    
    public static DeviceManager getInstance() {
        if (instance == null) {
            instance = new DeviceManager();
        }
        return instance;
    }

    /**
     * 统一获取业务实际应使用的主源
     * 规则：
     * - 有真实 master：返回真实 master
     * - 没有真实 master：返回 activeBeatSource 作为 fallback
     * 
     * @return 实际应使用的主源 player 编号（如 "1", "2", "3"），如果没有则返回 null
     */
    public String getEffectiveSource() {
        String realMaster = masterPlayer.get();
        if (realMaster != null) {
            return realMaster;
        }
        return activeBeatSource.get();
    }

    /**
     * 获取真实 master（来自 Beat Link MasterListener）
     * @return 真实 master player 编号，如果没有则返回 null
     */
    public String getRealMaster() {
        return masterPlayer.get();
    }

    /**
     * 获取当前发 beat 的设备
     * @return 当前发 beat 的 player 编号，如果没有则返回 null
     */
    public String getActiveBeatSource() {
        return activeBeatSource.get();
    }
    
    /**
     * Start all beat-link components
     */
    public void start() throws Exception {
        if (running) return;
        
        System.out.println("=== Starting DJ Link Service ===");
        
        // Start DeviceFinder (discovers devices on network)
        deviceFinder = DeviceFinder.getInstance();
        deviceFinder.start();
        System.out.println("DeviceFinder started");
        
        // Start VirtualCdj (becomes a CDJ on network)
        // IMPORTANT: Use device number in range 1-4 to allow MetadataFinder to query XDJ devices
        // When VirtualCdj is 1-4, the ConnectionManager will use our number directly to query
        // instead of trying to find an alternate player number (which fails because all 1-4 are taken)
        virtualCdj = VirtualCdj.getInstance();
        virtualCdj.setDeviceNumber((byte) 4);  // Changed from 7 to 4
        virtualCdj.start();
        System.out.println("VirtualCdj started (device 4) - allows metadata queries");
        
        // Start BeatFinder (monitors beat/ tempo)
        beatFinder = BeatFinder.getInstance();
        beatFinder.start();
        System.out.println("BeatFinder started");
        
        // Start MetadataFinder (for track metadata)
        metadataFinder = MetadataFinder.getInstance();
        metadataFinder.start();
        System.out.println("MetadataFinder started, isRunning=" + metadataFinder.isRunning());
        
        // Start BeatGridFinder (for beat grid data)
        beatGridFinder = BeatGridFinder.getInstance();
        beatGridFinder.start();
        System.out.println("BeatGridFinder started, isRunning=" + beatGridFinder.isRunning());
        
        // Start WaveformFinder (for waveform data)
        waveformFinder = WaveformFinder.getInstance();
        waveformFinder.start();
        System.out.println("WaveformFinder started, isRunning=" + waveformFinder.isRunning());
        
        // Start AnalysisTagFinder (for song structure/phrase data)
        analysisTagFinder = AnalysisTagFinder.getInstance();
        analysisTagFinder.start();
        System.out.println("AnalysisTagFinder started, isRunning=" + analysisTagFinder.isRunning());

        // Start ArtFinder (for album artwork)
        artFinder = ArtFinder.getInstance();
        artFinder.setRequestHighResolutionArt(false);
        artFinder.start();
        System.out.println("ArtFinder started, isRunning=" + artFinder.isRunning());
        
        // Add listeners only once (singletons keep listeners)
        if (!listenersAdded) {
            addListeners();
            listenersAdded = true;
        }

        lastSignalMs = System.currentTimeMillis();
        running = true;
        System.out.println("=== DJ Link Service Running ===");
    }
    
    /**
     * Add listeners for device updates
     */
    private void addListeners() {
        // Device announcement listener
        deviceFinder.addDeviceAnnouncementListener(new DeviceAnnouncementListener() {
            @Override
            public void deviceFound(DeviceAnnouncement announcement) {
                int dn = announcement.getDeviceNumber();
                PlayerState state = players.getOrDefault(dn, new PlayerState());
                state.deviceNumber = dn;
                state.lastUpdate = System.currentTimeMillis();
                players.put(dn, state);
                lastSignalMs = System.currentTimeMillis();
                System.out.println("📱 Device found: #" + dn + " " + announcement.getDeviceName());
            }
            
            @Override
            public void deviceLost(DeviceAnnouncement announcement) {
                int dn = announcement.getDeviceNumber();
                players.remove(dn);
                if (masterPlayer.get() != null && masterPlayer.get().equals(String.valueOf(dn))) {
                    masterPlayer.set(null);
                }
                lastSignalMs = System.currentTimeMillis();
                System.out.println("📱 Device lost: #" + dn + " (removed from active players)");
            }
        });
        
        // Virtual CDJ update listener - THIS IS WHERE STATUS COMES FROM
        virtualCdj.addUpdateListener(new DeviceUpdateListener() {
            @Override
            public void received(DeviceUpdate update) {
                if (update instanceof CdjStatus) {
                    CdjStatus status = (CdjStatus) update;
                    updatePlayerState(status.getDeviceNumber(), status);
                }
            }
        });
        
        // Beat listener - 只负责 bpm 和 activeBeatSource，不再负责 master 判定
        beatFinder.addBeatListener(new BeatListener() {
            @Override
            public void newBeat(Beat beat) {
                bpm.set((int) beat.getBpm());
                // 记录当前发 beat 的设备（用于 fallback）
                activeBeatSource.set("" + beat.getDeviceNumber());
            }
        });
        
        // Master listener - 从 Beat Link 获取真实 master
        beatFinder.addMasterListener(new MasterListener() {
            @Override
            public void masterChanged(DeviceUpdate update) {
                // 真实 master 来自 Beat Link 协议
                int masterDeviceNum = update.getDeviceNumber();
                masterPlayer.set("" + masterDeviceNum);
                System.out.println("🎚️ [BeatLink] Real master changed: device #" + masterDeviceNum);
            }
            
            @Override
            public void tempoChanged(double tempo) {
                // 可以选择记录或忽略
            }
        });
        
        // Track metadata listener
        metadataFinder.addTrackMetadataListener(new TrackMetadataListener() {
            @Override
            public void metadataChanged(TrackMetadataUpdate update) {
                System.out.println("🎵 Metadata update received: player=" + update.player + 
                    " metadata=" + (update.metadata != null ? update.metadata.getTitle() : "null"));
            }
        });

        // 媒体挂载变化时清理预热缓存，避免跨U盘误命中旧索引。
        metadataFinder.addMountListener(new MountListener() {
            @Override
            public void mediaMounted(SlotReference slotReference) {
                metadataWarmup.clear();
                System.out.println("📦 Media mounted on " + slotReference + ", metadata warmup cache cleared.");
            }

            @Override
            public void mediaUnmounted(SlotReference slotReference) {
                metadataWarmup.clear();
                System.out.println("📦 Media unmounted from " + slotReference + ", metadata warmup cache cleared.");
            }
        });
    }
    
    private void updatePlayerState(int deviceNumber, CdjStatus status) {
        // Check previous playing state BEFORE updating
        PlayerState state = players.getOrDefault(deviceNumber, new PlayerState());
        boolean wasPlaying = state.status != null && state.status.isPlaying();
        boolean isPlaying = status.isPlaying();
        
        // Update state
        state.deviceNumber = deviceNumber;
        state.status = status;
        state.lastUpdate = System.currentTimeMillis();
        lastSignalMs = System.currentTimeMillis();
        
        // If transitioned from not playing to playing, record start time
        if (!wasPlaying && isPlaying) {
            state.playStartTimeMs = System.currentTimeMillis();
            System.out.println("▶ Play started for Deck " + deviceNumber + " at " + state.playStartTimeMs);
        }
        
        // DEBUG: Print status info
        System.out.println("🔍 Player " + deviceNumber + " status: playing=" + status.isPlaying() + 
            " trackType=" + status.getTrackType() + " sourceSlot=" + status.getTrackSourceSlot());
        
        boolean validTrackRef = status.getTrackType() != null
                && status.getTrackSourceSlot() != null
                && status.getTrackType() != CdjStatus.TrackType.NO_TRACK
                && status.getTrackSourceSlot() != CdjStatus.TrackSourceSlot.NO_TRACK
                && status.getRekordboxId() > 0;

        // 预热：仅在有效曲目引用时异步拉取，减少 NO_TRACK 噪声。
        if (validTrackRef) {
            metadataWarmup.prefetchFromStatus(status, metadataFinder);
        }

        // Try to get track metadata for this player
        try {
            TrackMetadata meta = metadataFinder.getLatestMetadataFor(deviceNumber);
            if (meta != null) {
                if (validTrackRef) {
                    metadataLastLookup = "HIT(latest-cache)";
                    metadataLastLookupTs = System.currentTimeMillis();
                }
            } else if (validTrackRef) {
                // 兜底：从预热缓存查同一 DataReference。
                meta = metadataWarmup.getFromStatus(status);
                if (meta != null) {
                    metadataLastLookup = "HIT(warmup-cache)";
                    metadataLastLookupTs = System.currentTimeMillis();
                }
            }
            if (meta != null) {
                state.trackMeta = meta;
                System.out.println("✅ Got metadata for player " + deviceNumber + ": " + meta.getTitle());
            } else {
                if (validTrackRef) {
                    metadataLastLookup = "MISS";
                    metadataLastLookupTs = System.currentTimeMillis();
                }
                // Try getting metadata using different approach - from CdjStatus
                System.out.println("⚠️ metadata miss for player " + deviceNumber + ", warmupCacheSize=" + metadataWarmup.size());

                // Check if there's a data reference in the status
                if (status.getTrackType() != null) {
                    System.out.println("   Track type: " + status.getTrackType());
                }
                if (status.getTrackSourceSlot() != null) {
                    System.out.println("   Source slot: " + status.getTrackSourceSlot());
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Error getting metadata for player " + deviceNumber + ": " + e.getMessage());
        }
        
        players.put(deviceNumber, state);
    }
    
    /**
     * Get player status
     */
    public Map<String, Object> getPlayerStatus() {
        Map<String, Object> result = new HashMap<>();
        
        List<Map<String, Object>> playerList = new ArrayList<>();
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            PlayerState ps = entry.getValue();
            Map<String, Object> p = new HashMap<>();
            p.put("number", ps.deviceNumber);
            
            if (ps.status != null) {
                p.put("playing", ps.status.isPlaying());
                p.put("beat", ps.status.getBeatNumber());
                
                // getBpm() returns display BPM directly, no division needed
                int bpm = (int) ps.status.getBpm();
                if (bpm != 65535 && bpm > 0) {
                    p.put("bpm", (double) bpm);
                } else {
                    p.put("bpm", null);
                }
                
                // Add pitch - raw pitch 1048576 = 0%
                int rawPitch = (int) ps.status.getPitch();
                if (rawPitch != 0) {
                    p.put("pitch", (rawPitch / 1048576.0 - 1.0) * 100.0);
                } else {
                    p.put("pitch", null);  // rawPitch 0 means no track loaded or invalid
                }
            }
            
            playerList.add(p);
        }
        
        result.put("players", playerList);
        
        // Calculate global BPM - average of all playing players
        int playingBpm = 0;
        int playingCount = 0;
        for (Map<String, Object> player : playerList) {
            Boolean playing = (Boolean) player.get("playing");
            Number bpmVal = (Number) player.get("bpm");
            if (playing != null && playing && bpmVal != null) {
                playingBpm += bpmVal.doubleValue();
                playingCount++;
            }
        }
        result.put("bpm", playingCount > 0 ? playingBpm / playingCount : null);
        result.put("master", masterPlayer.get());
        result.put("activeBeatSource", activeBeatSource.get());  // 当前发 beat 的设备（fallback 用）
        result.put("online", players.size());
        // 预热缓存可视化：用于前端直接判断 metadata 预热是否在工作。
        result.put("metadataWarmupCacheSize", metadataWarmup.size());
        
        return result;
    }
    
    /**
     * Get track info - with detailed debugging
     */
    public Map<String, Object> getTrackInfo() {
        Map<String, Object> result = new HashMap<>();
        
        System.out.println("=== getTrackInfo() called ===");
        System.out.println("Players in map: " + players.keySet());
        
        List<Map<String, Object>> trackList = new ArrayList<>();
        
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            PlayerState ps = entry.getValue();
            Map<String, Object> t = new HashMap<>();
            t.put("number", ps.deviceNumber);
            t.put("playing", ps.status != null ? ps.status.isPlaying() : false);
            
            // Debug info
            String sourceSlot = (ps.status != null && ps.status.getTrackSourceSlot() != null) 
                ? ps.status.getTrackSourceSlot().name() : null;
            String trackType = (ps.status != null && ps.status.getTrackType() != null)
                ? ps.status.getTrackType().name() : null;
            
            t.put("sourceSlot", sourceSlot);
            t.put("trackType", trackType);
            
            // Check if we have metadata in state
            boolean hasMeta = ps.trackMeta != null;
            t.put("metadataFound", hasMeta);
            
            // Try to get fresh metadata
            try {
                TrackMetadata meta = metadataFinder.getLatestMetadataFor(ps.deviceNumber);
                if (meta != null) {
                    // getTitle() returns String, getArtist() and getAlbum() return SearchableItem with .label field
                    String title = meta.getTitle();
                    String artist = (meta.getArtist() != null) ? meta.getArtist().label : null;
                    String album = (meta.getAlbum() != null) ? meta.getAlbum().label : null;
                    
                    t.put("title", title);
                    t.put("artist", artist);
                    t.put("album", album);
                    t.put("duration", meta.getDuration());
                    System.out.println("✅ Fresh metadata for player " + ps.deviceNumber + ": " + title);
                } else {
                    // Also try using the status to get metadata
                    if (ps.status != null) {
                        // Try querying by device number through VirtualCdj's status
                        System.out.println("⚠️ No cached metadata for player " + ps.deviceNumber + 
                            ", trying different approaches...");
                        
                        // List what we know from status
                        System.out.println("   sourceSlot=" + sourceSlot + " trackType=" + trackType);
                    }
                    
                    t.put("title", null);
                    t.put("artist", null);
                    t.put("album", null);
                    t.put("duration", null);
                }
            } catch (Exception e) {
                System.out.println("❌ Error for player " + ps.deviceNumber + ": " + e.getMessage());
                t.put("title", null);
                t.put("artist", null);
                t.put("album", null);
                t.put("duration", null);
            }
            
            trackList.add(t);
        }
        
        result.put("players", trackList);
        
        return result;
    }
    
    /**
     * Get beat grid data for all players
     */
    public Map<String, Object> getBeatGrid() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> gridList = new ArrayList<>();
        
        System.out.println("🔍 Querying BeatGrid for online players...");
        
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            Integer playerNum = entry.getKey();
            if (playerNum == 4) continue; // Skip VirtualCdj
            
            PlayerState ps = entry.getValue();
            Map<String, Object> g = new HashMap<>();
            g.put("number", playerNum);
            
            try {
                BeatGrid grid = beatGridFinder.getLatestBeatGridFor(playerNum);
                if (grid != null) {
                    System.out.println("✅ Player " + playerNum + " beat grid found: " + grid.beatCount + " beats");
                    g.put("beatGridFound", true);
                    g.put("beatCount", grid.beatCount);
                    
                    // Build beat list (limit to first 16 for debugging)
                    // Note: time values from BeatGrid are in 1/1000 of a frame (0.5ms resolution)
                    List<Map<String, Object>> beats = new ArrayList<>();
                    int limit = Math.min(grid.beatCount, 16);
                    for (int i = 0; i < grid.beatCount; i++) {
                        if (i >= limit) break;
                        Map<String, Object> beat = new HashMap<>();
                        beat.put("index", i + 1);
                        // getBeatWithinBar expects 1-based index
                        beat.put("beatWithinBar", grid.getBeatWithinBar(i + 1));
                        // Convert raw BPM (x100) to display BPM
                        beat.put("bpm", grid.getBpm(i + 1) / 100.0);
                        // Convert from 1/1000 frame to milliseconds
                        beat.put("timeMs", grid.getTimeWithinTrack(i + 1));
                        beats.add(beat);
                    }
                    g.put("beats", beats);
                } else {
                    System.out.println("⚠️ Player " + playerNum + " no beat grid found");
                    g.put("beatGridFound", false);
                    g.put("beatCount", 0);
                    g.put("beats", new ArrayList<>());
                }
            } catch (Exception e) {
                System.out.println("❌ Player " + playerNum + " beat grid error: " + e.getMessage());
                g.put("beatGridFound", false);
                g.put("error", e.getMessage());
            }
            
            gridList.add(g);
        }
        
        result.put("players", gridList);
        return result;
    }
    
    /**
     * Get cue points for all players
     */
    public Map<String, Object> getCuePoints() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> cueList = new ArrayList<>();
        
        System.out.println("🔍 Querying CueList for online players...");
        
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            Integer playerNum = entry.getKey();
            if (playerNum == 4) continue; // Skip VirtualCdj
            
            PlayerState ps = entry.getValue();
            Map<String, Object> c = new HashMap<>();
            c.put("number", playerNum);
            
            try {
                // Try to get CueList from metadata
                TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNum);
                if (meta != null && meta.getCueList() != null) {
                    CueList cues = meta.getCueList();
                    System.out.println("✅ Player " + playerNum + " cue list found: " + cues.entries.size() + " entries");
                    c.put("cueFound", true);
                    
                    List<Map<String, Object>> cuesData = new ArrayList<>();
                    for (CueList.Entry cue : cues.entries) {
                        Map<String, Object> cueEntry = new HashMap<>();
                        // Determine cue type: hot cue (non-zero hotCueNumber) vs memory cue
                        String cueType = (cue.hotCueNumber > 0) ? "HOT_CUE" : "MEMORY_CUE";
                        cueEntry.put("type", cueType);
                        cueEntry.put("index", cue.hotCueNumber > 0 ? cue.hotCueNumber : cue.cuePosition);
                        // cueTime is in milliseconds
                        cueEntry.put("timeMs", cue.cueTime);
                        // cuePosition is in 1/1000 frame (0.5ms resolution)
                        cueEntry.put("position", cue.cuePosition);
                        cueEntry.put("isLoop", cue.isLoop);
                        cuesData.add(cueEntry);
                    }
                    c.put("cues", cuesData);
                } else {
                    System.out.println("⚠️ Player " + playerNum + " no cue list found");
                    c.put("cueFound", false);
                    c.put("cues", new ArrayList<>());
                }
            } catch (Exception e) {
                System.out.println("❌ Player " + playerNum + " cue list error: " + e.getMessage());
                c.put("cueFound", false);
                c.put("error", e.getMessage());
            }
            
            cueList.add(c);
        }
        
        result.put("players", cueList);
        return result;
    }
    
    /**
     * Get waveform data for all players
     */
    public Map<String, Object> getWaveform() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> waveList = new ArrayList<>();
        
        System.out.println("🔍 Querying Waveform for online players...");
        
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            Integer playerNum = entry.getKey();
            if (playerNum == 4) continue; // Skip VirtualCdj
            
            PlayerState ps = entry.getValue();
            Map<String, Object> w = new HashMap<>();
            w.put("number", playerNum);
            
            try {
                // Get preview waveform
                WaveformPreview preview = waveformFinder.getLatestPreviewFor(playerNum);
                if (preview != null) {
                    System.out.println("✅ Player " + playerNum + " preview waveform found: " + preview.segmentCount + " segments");
                    w.put("previewWaveformFound", true);
                    w.put("previewLength", preview.segmentCount);
                    
                    // Limit samples for debugging
                    int limit = Math.min(preview.segmentCount, 20);
                    int[] previewSamples = new int[limit];
                    for (int i = 0; i < limit; i++) {
                        previewSamples[i] = preview.segmentHeight(i, false);
                    }
                    // Convert to list for JSON serialization
                    List<Integer> previewSampleList = new ArrayList<>();
                    for (int s : previewSamples) previewSampleList.add(s);
                    w.put("previewSample", previewSampleList);
                } else {
                    System.out.println("⚠️ Player " + playerNum + " no preview waveform found");
                    w.put("previewWaveformFound", false);
                    w.put("previewLength", 0);
                    w.put("previewSample", new int[]{});
                }
                
                // Get detailed waveform
                WaveformDetail detail = waveformFinder.getLatestDetailFor(playerNum);
                if (detail != null) {
                    w.put("detailedWaveformFound", true);
                    w.put("detailLength", detail.getFrameCount());
                } else {
                    w.put("detailedWaveformFound", false);
                    w.put("detailLength", 0);
                }
                
            } catch (Exception e) {
                System.out.println("❌ Player " + playerNum + " waveform error: " + e.getMessage());
                w.put("previewWaveformFound", false);
                w.put("detailedWaveformFound", false);
                w.put("error", e.getMessage());
            }
            
            waveList.add(w);
        }
        
        result.put("players", waveList);
        return result;
    }
    
    /**
     * Get phrase/song structure data for all players
     */
    public Map<String, Object> getPhrase() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> phraseList = new ArrayList<>();
        
        System.out.println("🔍 Querying Song Structure (Phrase) for online players...");
        
        // First, let's check what analysis tags are actually loaded
        try {
            Map loaded = analysisTagFinder.getLoadedAnalysisTags();
            System.out.println("📋 Loaded analysis tags: " + (loaded != null ? loaded.keySet() : "null"));
        } catch (Exception e) {
            System.out.println("⚠️ Could not get loaded analysis tags: " + e.getMessage());
        }
        
        // Song structure is stored in analysis tags - try different extensions
        String[] extensions = {"PNAV", "ANALYZZ", "ZXML", ""};
        String[] typeTags = {"SSTR", "SONG", "STRUCTURE", ""};
        
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            Integer playerNum = entry.getKey();
            if (playerNum == 4) continue; // Skip VirtualCdj
            
            PlayerState ps = entry.getValue();
            Map<String, Object> p = new HashMap<>();
            p.put("number", playerNum);
            
            // First get track info for debugging
            try {
                TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNum);
                if (meta != null) {
                    System.out.println("   Player " + playerNum + " track: " + meta.getTitle() + 
                        ", source: " + ps.status.getTrackSourceSlot() + 
                        ", type: " + ps.status.getTrackType());
                }
            } catch (Exception e) {
                // ignore
            }
            
            // Try different combinations to find song structure
            boolean found = false;
            for (String ext : extensions) {
                for (String tag : typeTags) {
                    if (ext.isEmpty() && tag.isEmpty()) continue;
                    try {
                        Object structure = analysisTagFinder.getLatestTrackAnalysisFor(playerNum, ext, tag);
                        if (structure != null) {
                            System.out.println("✅ Player " + playerNum + " found structure: ext=" + ext + ", tag=" + tag + ", class=" + structure.getClass().getSimpleName());
                            p.put("phraseFound", true);
                            p.put("fileExtension", ext);
                            p.put("typeTag", tag);
                            p.put("structureClass", structure.getClass().getSimpleName());
                            found = true;
                            break;
                        }
                    } catch (Exception e) {
                        // continue
                    }
                }
                if (found) break;
            }
            
            if (!found) {
                System.out.println("⚠️ Player " + playerNum + " no song structure found");
                p.put("phraseFound", false);
            }
            
            phraseList.add(p);
        }
        
        result.put("players", phraseList);
        return result;
    }
    
    /**
     * Unified AI interface - aggregates all player data for AI/lighting decision making
     */
    public Map<String, Object> getAiPlayers() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> playerList = new ArrayList<>();
        
        // 真实 master（来自 MasterListener），如果没有则用 activeBeatSource 作为 fallback
        String currentMaster = masterPlayer.get();
        if (currentMaster == null) {
            currentMaster = activeBeatSource.get();  // fallback
        }
        Integer currentMasterNum = null;
        try {
            if (currentMaster != null) currentMasterNum = Integer.parseInt(currentMaster);
        } catch (Exception ignore) {}
        long now = System.currentTimeMillis() / 1000;
        long nowMs = System.currentTimeMillis();
        
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            Integer playerNum = entry.getKey();
            if (playerNum < 1 || playerNum > 4 || playerNum == 4) continue; // Keep only real decks 1-3, skip VirtualCdj(4) and non-deck devices
            
            PlayerState ps = entry.getValue();
            boolean active = ps != null && (nowMs - ps.lastUpdate) <= PLAYER_STALE_MS;
            if (!active) continue; // treat stale players as offline

            Map<String, Object> p = new HashMap<>();
            p.put("number", playerNum);
            p.put("active", true);
            p.put("master", currentMasterNum != null && playerNum.intValue() == currentMasterNum.intValue());
            p.put("activeBeatSource", activeBeatSource.get());  // 新增：当前发 beat 的设备
            
            // Real-time status
            if (ps.status != null) {
                p.put("playing", ps.status.isPlaying());
                p.put("onAir", ps.status.isOnAir());
                p.put("beat", ps.status.getBeatNumber());

                int bpm = (int) ps.status.getBpm();
                if (bpm != 65535 && bpm > 0) {
                    p.put("bpm", bpm / 100.0); // display BPM
                } else {
                    p.put("bpm", null);
                }

                int rawPitch = (int) ps.status.getPitch();
                if (rawPitch != 0) {
                    p.put("pitch", (rawPitch / 1048576.0 - 1.0) * 100.0);
                } else {
                    p.put("pitch", null);
                }

                // beatTimeMs comes from beat-grid timeline (not true transport clock)
                Integer beatTimeMs = null;
                try {
                    BeatGrid grid = beatGridFinder.getLatestBeatGridFor(playerNum);
                    if (grid != null) {
                        int beatNum = ps.status.getBeatNumber();
                        if (beatNum > 0 && beatNum <= grid.beatCount) {
                            // getTimeWithinTrack already returns milliseconds
                            beatTimeMs = (int) grid.getTimeWithinTrack(beatNum);
                            p.put("beatTimeMs", beatTimeMs);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }

                // currentTimeMs: currently derived from beat grid time (best available)
                // NOTE: this is approximate when grid/transport diverges.
                if (beatTimeMs != null) {
                    p.put("currentTimeMs", beatTimeMs);
                }
            } else {
                p.put("playing", false);
                p.put("onAir", false);
                p.put("beat", null);
                p.put("bpm", null);
                p.put("pitch", null);
            }
            
            // Track metadata
            Map<String, Object> track = new HashMap<>();
            try {
                TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNum);
                if (meta != null) {
                    track.put("title", meta.getTitle());
                    track.put("artist", meta.getArtist() != null ? meta.getArtist().label : null);
                    track.put("album", meta.getAlbum() != null ? meta.getAlbum().label : null);
                    track.put("duration", meta.getDuration());
                    track.put("trackType", ps.status != null ? ps.status.getTrackType().toString() : null);
                    track.put("sourceSlot", ps.status != null ? ps.status.getTrackSourceSlot().toString() : null);

                    // Artwork metadata
                    track.put("artworkId", meta.getArtworkId());
                    track.put("artworkUrl", "/api/players/artwork?player=" + playerNum + "&id=" + meta.getArtworkId());
                    try {
                        AlbumArt art = artFinder != null ? artFinder.getLatestArtFor(playerNum) : null;
                        track.put("artworkAvailable", art != null);
                    } catch (Exception ignore) {
                        track.put("artworkAvailable", false);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            p.put("track", track);

            // Time helpers for UI
            if (track.get("duration") instanceof Number) {
                int durationSec = ((Number) track.get("duration")).intValue();
                int durationMs = durationSec * 1000;
                p.put("durationMs", durationMs);
                if (p.get("beatTimeMs") instanceof Number) {
                    int currentTimeMs = ((Number) p.get("beatTimeMs")).intValue();
                    p.put("currentTimeMs", currentTimeMs);
                    p.put("remainingTimeMs", Math.max(0, durationMs - currentTimeMs));
                }
            }
            
            // Analysis data summary
            Map<String, Object> analysis = new HashMap<>();
            
            // Beat grid
            try {
                BeatGrid grid = beatGridFinder.getLatestBeatGridFor(playerNum);
                analysis.put("beatGridFound", grid != null);
                analysis.put("beatCount", grid != null ? grid.beatCount : 0);

                if (grid != null) {
                    List<Integer> beatTicksMs = new ArrayList<>(grid.beatCount);
                    List<Integer> beatTicksInBar = new ArrayList<>(grid.beatCount);
                    int maxBeats = Math.min(grid.beatCount, 2048);
                    for (int bi = 1; bi <= maxBeats; bi++) {
                        beatTicksMs.add((int) grid.getTimeWithinTrack(bi));
                        beatTicksInBar.add(grid.getBeatWithinBar(bi));
                    }
                    analysis.put("beatTicksMs", beatTicksMs);
                    analysis.put("beatTicksInBar", beatTicksInBar);
                }
            } catch (Exception e) {
                analysis.put("beatGridFound", false);
                analysis.put("beatCount", 0);
            }
            
            // Cues
            boolean cueFound = false;
            int cueCount = 0;
            boolean hasHotCues = false;
            int hotCueCount = 0;
            List<Integer> hotCueTimesMs = new ArrayList<>();
            try {
                TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNum);
                if (meta != null && meta.getCueList() != null) {
                    cueFound = true;
                    cueCount = meta.getCueList().entries.size();
                    for (CueList.Entry cue : meta.getCueList().entries) {
                        if (cue.hotCueNumber > 0) {
                            hasHotCues = true;
                            hotCueCount++;
                            hotCueTimesMs.add((int) cue.cueTime);
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            analysis.put("cueFound", cueFound);
            analysis.put("cueCount", cueCount);
            analysis.put("hasHotCues", hasHotCues);
            analysis.put("hotCueCount", hotCueCount);
            analysis.put("hotCueTimesMs", hotCueTimesMs);
            
            // Waveform
            try {
                WaveformPreview preview = waveformFinder.getLatestPreviewFor(playerNum);
                analysis.put("previewWaveformFound", preview != null);
                analysis.put("previewLength", preview != null ? preview.segmentCount : 0);
                
                // Add lightweight preview sample (max 60 points)
                if (preview != null) {
                    List<Integer> previewSample = new ArrayList<>();
                    int total = preview.segmentCount;
                    int step = Math.max(1, total / 60);  // Max 60 points
                    for (int i = 0; i < total; i += step) {
                        previewSample.add(preview.segmentHeight(i, false));
                    }
                    analysis.put("previewSample", previewSample);
                }
            } catch (Exception e) {
                analysis.put("previewWaveformFound", false);
                analysis.put("previewLength", 0);
            }
            
            try {
                WaveformDetail detail = waveformFinder.getLatestDetailFor(playerNum);
                analysis.put("detailedWaveformFound", detail != null);
                analysis.put("detailLength", detail != null ? detail.getFrameCount() : 0);

                // High-resolution RGB waveform sample for CDJ3000-like rendering
                if (detail != null) {
                    int frames = Math.max(1, detail.getFrameCount());
                    int outCols = Math.min(900, frames);
                    int scale = Math.max(1, frames / outCols);

                    List<Integer> detailHeights = new ArrayList<>(outCols);
                    List<Integer> detailColors = new ArrayList<>(outCols);

                    for (int i = 0; i < outCols; i++) {
                        int seg = Math.min(frames - 1, i * scale);
                        int h = detail.segmentHeight(seg, scale); // 0..31
                        detailHeights.add(h);

                        java.awt.Color c = detail.segmentColor(seg, scale);
                        int packed = ((c.getRed() & 0xff) << 16) | ((c.getGreen() & 0xff) << 8) | (c.getBlue() & 0xff);
                        detailColors.add(packed);
                    }

                    analysis.put("detailSampleScale", scale);
                    analysis.put("detailSampleHeights", detailHeights);
                    analysis.put("detailSampleColors", detailColors);
                }
            } catch (Exception e) {
                analysis.put("detailedWaveformFound", false);
                analysis.put("detailLength", 0);
            }
            
            p.put("analysis", analysis);
            
            // Current section
            try {
                BeatGrid grid = beatGridFinder.getLatestBeatGridFor(playerNum);
                if (grid != null) {
                    int sectionSize = 32;
                    int sectionCount = (int) Math.ceil((double) grid.beatCount / sectionSize);
                    int currentBeat = ps.status != null ? ps.status.getBeatNumber() : -1;
                    
                    // Find current section
                    for (int si = 0; si < sectionCount; si++) {
                        int startBeat = si * sectionSize + 1;
                        int endBeat = Math.min((si + 1) * sectionSize, grid.beatCount);
                        
                        if (currentBeat > 0 && currentBeat >= startBeat && currentBeat <= endBeat) {
                            long startTimeMs = grid.getTimeWithinTrack(startBeat);
                            long endTimeMs = grid.getTimeWithinTrack(endBeat);
                            
                            // Calculate energy
                            WaveformPreview wave = waveformFinder.getLatestPreviewFor(playerNum);
                            double energy = 0.0;
                            if (wave != null) {
                                int startSample = (int) ((startBeat * 1.0 / grid.beatCount) * wave.segmentCount);
                                int endSample = (int) ((endBeat * 1.0 / grid.beatCount) * wave.segmentCount);
                                int sampleCount = 0;
                                long sampleSum = 0;
                                for (int i = startSample; i < endSample && i < wave.segmentCount; i++) {
                                    sampleSum += wave.segmentHeight(i, false);
                                    sampleCount++;
                                }
                                if (sampleCount > 0) {
                                    energy = Math.min(1.0, (sampleSum / sampleCount) / 127.0);
                                }
                            }
                            
                            Map<String, Object> currentSection = new HashMap<>();
                            currentSection.put("index", si + 1);
                            currentSection.put("startBeat", startBeat);
                            currentSection.put("endBeat", endBeat);
                            currentSection.put("startTimeMs", startTimeMs);
                            currentSection.put("endTimeMs", endTimeMs);
                            currentSection.put("energy", Math.round(energy * 100.0) / 100.0);
                            currentSection.put("type", "UNKNOWN");
                            currentSection.put("confidence", 0.5);
                            currentSection.put("reason", "calculated from beatgrid+waveform");
                            
                            p.put("currentSection", currentSection);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            
            // AI Summary
            Map<String, Object> summary = new HashMap<>();
            boolean isPlaying = Boolean.TRUE.equals(p.get("playing"));
            boolean hasBpm = p.get("bpm") != null;
            boolean hasBeatGrid = Boolean.TRUE.equals(analysis.get("beatGridFound"));
            boolean hasWaveform = Boolean.TRUE.equals(analysis.get("previewWaveformFound"));
            boolean hasSection = p.containsKey("currentSection");
            
            if (!isPlaying) {
                summary.put("readiness", "IDLE");
            } else if (hasBpm && hasBeatGrid && hasWaveform && hasSection) {
                summary.put("readiness", "READY");
            } else if (isPlaying && hasBpm) {
                summary.put("readiness", "PARTIAL");
            } else {
                summary.put("readiness", "INSUFFICIENT");
            }
            
            // Energy level
            Map<String, Object> currentSection = (Map<String, Object>) p.get("currentSection");
            if (currentSection != null) {
                Double energy = (Double) currentSection.get("energy");
                String sectionType = (String) currentSection.get("type");
                summary.put("sectionType", sectionType != null ? sectionType : "UNKNOWN");
                if (energy != null) {
                    if (energy < 0.3) {
                        summary.put("energyLevel", "LOW");
                    } else if (energy < 0.6) {
                        summary.put("energyLevel", "MEDIUM");
                    } else {
                        summary.put("energyLevel", "HIGH");
                    }
                } else {
                    summary.put("energyLevel", "UNKNOWN");
                }
            } else {
                summary.put("energyLevel", "UNKNOWN");
                summary.put("sectionType", "UNKNOWN");
            }
            
            summary.put("hasHotCues", hasHotCues);
            p.put("summary", summary);
            
            playerList.add(p);
        }
        
        // Sort by player number
        playerList.sort((a, b) -> ((Integer)a.get("number")).compareTo((Integer)b.get("number")));
        
        result.put("players", playerList);
        result.put("online", playerList.size());
        result.put("master", masterPlayer.get());  // 真实 master（来自 Beat Link）
        result.put("effectiveSource", currentMaster != null ? currentMaster : activeBeatSource.get());  // 实际可用源
        result.put("activeBeatSource", activeBeatSource.get());
        result.put("updatedAt", now);
        result.put("ruleVersion", "sections-mvp-v2");
        
        return result;
    }
    
    /**
     * Unified player state - neutral endpoint for AI/rule/trigger systems
     */
    public Map<String, Object> getPlayersState() {
        Map<String, Object> result = getAiPlayers();
        
        // Transform summary to decision and add trigger-friendly fields
        List<Map<String, Object>> players = (List<Map<String, Object>>) result.get("players");
        String currentMaster = masterPlayer.get();

        // Auto-stop scanning when idle for a while and no active devices
        long nowMs = System.currentTimeMillis();
        if (running && (players == null || players.isEmpty()) && (nowMs - lastSignalMs) > SCAN_AUTO_STOP_MS) {
            System.out.println("⏹ Auto-stop scan: idle for " + (nowMs - lastSignalMs) + "ms");
            stop();
            result = getAiPlayers();
            players = (List<Map<String, Object>>) result.get("players");
        }
        
        for (Map<String, Object> p : players) {
            Integer playerNum = (Integer) p.get("number");
            
            // Add trigger-friendly fields
            if (!p.containsKey("active")) p.put("active", true);
            p.put("triggerKey", "player-" + playerNum);
            
            // Fix master calculation
            boolean isMaster = currentMaster != null && playerNum == Integer.parseInt(currentMaster);
            p.put("master", isMaster);
            
            // Transform summary to decision
            Map<String, Object> summary = (Map<String, Object>) p.get("summary");
            Map<String, Object> decision = new HashMap<>();
            
            if (summary != null) {
                decision.put("energyLevel", summary.get("energyLevel"));
                decision.put("sectionType", summary.get("sectionType"));
                decision.put("hasHotCues", summary.get("hasHotCues"));
                decision.put("ready", "READY".equals(summary.get("readiness")));
                decision.put("mode", summary.get("readiness"));
            }
            
            p.put("decision", decision);
            p.put("canTrigger", decision.get("ready"));
            p.remove("summary");
        }

        // 页面可视化：预热缓存当前大小（用于判断预热是否在工作）
        result.put("metadataWarmupCacheSize", metadataWarmup.size());
        result.put("metadataLastLookup", metadataLastLookup);
        result.put("metadataLastLookupTs", metadataLastLookupTs);
        
        return result;
    }

    /**
     * Mixer-focused state (currently on-air centric).
     * Kept separate to avoid mixing player UI fields and mixer telemetry.
     */
    public Map<String, Object> getMixerState() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> channels = new ArrayList<>();
        long nowMs = System.currentTimeMillis();

        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            Integer playerNum = entry.getKey();
            if (playerNum < 1 || playerNum > 4 || playerNum == 4) continue;

            PlayerState ps = entry.getValue();
            boolean active = ps != null && (nowMs - ps.lastUpdate) <= PLAYER_STALE_MS;

            Map<String, Object> ch = new HashMap<>();
            ch.put("player", playerNum);
            ch.put("active", active);
            ch.put("playing", ps != null && ps.status != null && ps.status.isPlaying());
            ch.put("onAir", ps != null && ps.status != null && ps.status.isOnAir());
            channels.add(ch);
        }

        channels.sort((a, b) -> ((Integer)a.get("player")).compareTo((Integer)b.get("player")));
        result.put("channels", channels);
        result.put("updatedAt", System.currentTimeMillis() / 1000);
        result.put("source", "beat-link/CdjStatus.isOnAir");
        return result;
    }

    /**
     * Helper: Get time in ms for a specific beat from beatgrid
     * Returns null if beatgrid not available
     */
    public Integer getBeatTimeMs(int playerNumber, int beatNumber) {
        try {
            BeatGrid grid = beatGridFinder.getLatestBeatGridFor(playerNumber);
            if (grid != null && beatNumber > 0 && beatNumber <= grid.beatCount) {
                return (int)(grid.getTimeWithinTrack(beatNumber));  // Convert to ms
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
    
    /**
     * Get trigger events based on state changes
     */
    public Map<String, Object> getTriggerEvents() {
        Map<String, Object> state = getPlayersState();
        List<Map<String, Object>> players = (List<Map<String, Object>>) state.get("players");
        String master = (String) state.get("master");
        
        TriggerEngine engine = TriggerEngine.getInstance();
        List<Map<String, Object>> events = engine.processStates(players, master);
        
        Map<String, Object> result = new HashMap<>();
        result.put("engineVersion", "trigger-mvp-v2");
        result.put("updatedAt", System.currentTimeMillis() / 1000);
        result.put("eventCount", events.size());
        result.put("events", events);
        
        return result;
    }


    
    /**
     * Get inferred sections based on beat grid and waveform analysis
     * This is a heuristic-based approach since native phrase data is not available
     */
    public Map<String, Object> getSections() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> sectionList = new ArrayList<>();
        
        System.out.println("🔍 Inferring sections for online players...");
        
        for (Map.Entry<Integer, PlayerState> entry : players.entrySet()) {
            Integer playerNum = entry.getKey();
            if (playerNum == 4) continue; // Skip VirtualCdj
            
            PlayerState ps = entry.getValue();
            Map<String, Object> s = new HashMap<>();
            s.put("number", playerNum);
            
            try {
                // Get beat grid
                BeatGrid grid = beatGridFinder.getLatestBeatGridFor(playerNum);
                // Get waveform
                WaveformPreview wave = waveformFinder.getLatestPreviewFor(playerNum);
                // Get cue points for boundary correction
                List<Long> cueTimes = new ArrayList<>();
                try {
                    TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNum);
                    if (meta != null && meta.getCueList() != null) {
                        for (CueList.Entry cue : meta.getCueList().entries) {
                            cueTimes.add(cue.cueTime);
                        }
                    }
                } catch (Exception e) {
                    // ignore cue errors
                }
                
                // Get current playing position
                int currentBeat = -1;
                if (ps.status != null) {
                    currentBeat = ps.status.getBeatNumber();
                }
                
                if (grid != null) {
                    System.out.println("✅ Player " + playerNum + " inferring sections from beat grid (" + grid.beatCount + " beats)");
                    String inferenceMethod = (cueTimes.size() > 0) ? "beatgrid+waveform+cues" : "beatgrid+waveform";
                    s.put("sectionsFound", true);
                    s.put("inference", inferenceMethod);
                    s.put("ruleVersion", "sections-mvp-v2");
                    
                    // Section size: 32 beats per section (standard for 8-bar blocks)
                    int sectionSize = 32;
                    int sectionCount = (int) Math.ceil((double) grid.beatCount / sectionSize);
                    
                    // First find the actual starting beat (grid may not start at beat 1)
                    int firstBeat = 1;
                    long firstBeatTime = grid.getTimeWithinTrack(firstBeat);
                    
                    List<Map<String, Object>> sections = new ArrayList<>();
                    double prevEnergy = -1;
                    int currentSectionIdx = -1;
                    
                    for (int si = 0; si < sectionCount; si++) {
                        int startBeat = si * sectionSize + firstBeat;
                        int endBeat = Math.min((si + 1) * sectionSize, grid.beatCount);
                        
                        // Apply cue-based boundary correction if nearby
                        // If cue is within ±8 beats of boundary, snap to cue
                        for (Long cueTime : cueTimes) {
                            long cueBeatMs = cueTime;
                            long startBeatMs = grid.getTimeWithinTrack(startBeat);
                            long endBeatMs = grid.getTimeWithinTrack(endBeat);
                            
                            // Check if cue is near start boundary
                            if (Math.abs(cueBeatMs - startBeatMs) < 2000) { // within 2 seconds
                                startBeatMs = cueBeatMs;
                            }
                            // Check if cue is near end boundary
                            if (Math.abs(cueBeatMs - endBeatMs) < 2000) {
                                endBeatMs = cueBeatMs;
                            }
                        }
                        
                        // Calculate time boundaries
                        long startTimeMs = grid.getTimeWithinTrack(startBeat);
                        long endTimeMs = grid.getTimeWithinTrack(endBeat);
                        
                        // Calculate energy from waveform
                        double energy = 0.0;
                        if (wave != null) {
                            int startSample = (int) ((startBeat * 1.0 / grid.beatCount) * wave.segmentCount);
                            int endSample = (int) ((endBeat * 1.0 / grid.beatCount) * wave.segmentCount);
                            int sampleCount = 0;
                            long sampleSum = 0;
                            for (int i = startSample; i < endSample && i < wave.segmentCount; i++) {
                                sampleSum += wave.segmentHeight(i, false);
                                sampleCount++;
                            }
                            if (sampleCount > 0) {
                                energy = Math.min(1.0, (sampleSum / sampleCount) / 127.0);
                            }
                        }
                        
                        // Calculate energy delta (current - previous, positive = rising)
                        Double energyDelta = null;
                        if (prevEnergy >= 0) {
                            energyDelta = Math.round((energy - prevEnergy) * 100.0) / 100.0;
                        }
                        
                        // Determine section type based on energy and trends - MUST match reason
                        String type;
                        String reason;
                        double confidence = 0.5;
                        
                        // Priority 1: First section
                        if (si == 0) {
                            type = "INTRO";
                            reason = "first section";
                            confidence = 0.9;
                        } 
                        // Priority 2: Last section
                        else if (si == sectionCount - 1) {
                            type = "OUTRO";
                            reason = "last section";
                            confidence = 0.9;
                        }
                        // Priority 3: Clear energy DROP (significant drop from previous)
                        else if (energyDelta != null && energyDelta < -0.15) {
                            type = "BREAK";
                            reason = "energy dropped from " + prevEnergy;
                            confidence = 0.7;
                        }
                        // Priority 4: Clear BUILD (significant rise)
                        else if (energyDelta != null && energyDelta > 0.15) {
                            type = "BUILD";
                            reason = "energy rising from " + prevEnergy;
                            confidence = 0.6;
                        }
                        // Priority 5: High stable energy = DROP
                        else if (energy >= 0.6) {
                            type = "DROP";
                            reason = "high stable energy";
                            confidence = 0.7;
                        }
                        // Priority 6: Low energy
                        else if (energy < 0.3) {
                            type = "BREAK";
                            reason = "low energy";
                            confidence = 0.6;
                        }
                        // Priority 7: Moderate energy = BUILD
                        else if (energy >= 0.3 && energy < 0.6) {
                            type = "BUILD";
                            reason = "moderate energy";
                            confidence = 0.5;
                        }
                        // Default: Unknown
                        else {
                            type = "UNKNOWN";
                            reason = "insufficient evidence";
                            confidence = 0.3;
                        }
                        
                        prevEnergy = energy;
                        
                        // Check if this is the current section
                        if (currentBeat > 0 && currentBeat >= startBeat && currentBeat <= endBeat) {
                            currentSectionIdx = si;
                        }
                        
                        Map<String, Object> section = new HashMap<>();
                        section.put("index", si + 1);
                        section.put("type", type);
                        section.put("startBeat", startBeat);
                        section.put("endBeat", endBeat);
                        section.put("startTimeMs", startTimeMs);
                        section.put("endTimeMs", endTimeMs);
                        section.put("energy", Math.round(energy * 100.0) / 100.0);
                        section.put("energyDelta", energyDelta);
                        section.put("confidence", confidence);
                        section.put("reason", reason);
                        sections.add(section);
                    }
                    
                    s.put("sections", sections);
                    
                    // Current section based on playback position
                    if (currentSectionIdx >= 0) {
                        Map<String, Object> currentSection = sections.get(currentSectionIdx);
                        s.put("currentSection", currentSection);
                        s.put("currentBeat", currentBeat);
                    }
                    
                } else {
                    System.out.println("⚠️ Player " + playerNum + " no beat grid for section inference");
                    s.put("sectionsFound", false);
                    s.put("reason", "no_beat_grid");
                }
            } catch (Exception e) {
                System.out.println("❌ Player " + playerNum + " section inference error: " + e.getMessage());
                s.put("sectionsFound", false);
                s.put("error", e.getMessage());
            }
            
            sectionList.add(s);
        }
        
        result.put("players", sectionList);
        return result;
    }
    
    /**
     * Stop all components
     */
    public synchronized Map<String, Object> setScanning(boolean enabled) {
        try {
            if (enabled) {
                start();
            } else {
                stop();
            }
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage(), "scanning", running);
        }
        return getScanStatus();
    }

    public Map<String, Object> getScanStatus() {
        long now = System.currentTimeMillis();
        long idleMs = Math.max(0, now - lastSignalMs);
        return Map.of(
            "ok", true,
            "scanning", running,
            "online", players.size(),
            "idleMs", idleMs,
            "autoStopMs", SCAN_AUTO_STOP_MS
        );
    }

    public void stop() {
        running = false;
        
        try {
            if (virtualCdj != null) virtualCdj.stop();
            if (deviceFinder != null) deviceFinder.stop();
            if (beatFinder != null) beatFinder.stop();
            if (metadataFinder != null) metadataFinder.stop();
            if (beatGridFinder != null) beatGridFinder.stop();
            if (waveformFinder != null) waveformFinder.stop();
            if (analysisTagFinder != null) analysisTagFinder.stop();
            if (artFinder != null) artFinder.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }

        players.clear();
        metadataWarmup.clear();
        metadataWarmup.shutdown();
        System.out.println("=== DJ Link Service Stopped ===");
    }
    
    /**
     * Player state container
     */
    public static class PlayerState {
        public int deviceNumber;
        public DeviceUpdate device;
        public CdjStatus status;
        public TrackMetadata trackMeta;
        public long lastUpdate;
        public long playStartTimeMs;  // When playback started
        public long savedPositionMs;  // Saved position when paused
    }
}
