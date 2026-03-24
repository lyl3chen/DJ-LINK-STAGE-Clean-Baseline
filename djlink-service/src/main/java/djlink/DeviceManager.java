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
    private TimeFinder timeFinder;  // BLT 方式：用于获取单个 player 最新状态

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
     * 获取真实 master（beat-link 通用方法）
     * 
     * 直接使用 VirtualCdj.getLatestStatus() 获取最新状态，与 BLT 思路一致
     *
     * @return 真实 master player 编号，如果没有则返回 null
     * 
     * 优先级（与 BLT 一致）：
     * 1. TimeFinder.getLatestUpdateFor(playerNum) + isTempoMaster() - 逐个检查
     * 2. VirtualCdj.getTempoMaster() - 辅助
     * 3. MasterListener 事件 - 辅助
     * 4. 以上都没有 → 返回 null
     */
    public String resolveRealMaster() {
        // 1. 第一优先：TimeFinder.getLatestUpdateFor(playerNum) + isTempoMaster()（与 BLT 一致）
        try {
            if (timeFinder != null && timeFinder.isRunning()) {
                // 遍历 player 1-4，逐个检查
                for (int playerNum = 1; playerNum <= 4; playerNum++) {
                    DeviceUpdate update = timeFinder.getLatestUpdateFor(playerNum);
                    if (update instanceof CdjStatus) {
                        CdjStatus status = (CdjStatus) update;
                        if (status.isTempoMaster()) {
                            return String.valueOf(playerNum);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. 第二优先：VirtualCdj.getTempoMaster()（辅助）
        try {
            DeviceUpdate tempoMaster = virtualCdj.getTempoMaster();
            if (tempoMaster != null) {
                return String.valueOf(tempoMaster.getDeviceNumber());
            }
        } catch (Exception ignored) {}

        // 3. 第三优先：MasterListener 事件（辅助参考）
        String listenerMaster = masterPlayer.get();
        if (listenerMaster != null) {
            return listenerMaster;
        }

        // 4. 以上都没有 → 返回 null
        return null;
    }

    /**
     * 获取业务实际使用的主源
     *
     * 规则：
     * - realMaster 可用：effectiveSource = realMaster
     * - 拿不到：effectiveSource = null
     *
     * @return 实际使用的主源 player 编号
     */
    public String getEffectiveSource() {
        return resolveRealMaster();
    }

    /**
     * 获取真实 master（用于 Dashboard 显示）
     * @return 真实 master player 编号
     */
    public String getRealMaster() {
        return resolveRealMaster();
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
        // Use device number in range 1-4 to allow MetadataFinder to query all players
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

        // Start TimeFinder (BLT 方式：用于获取单个 player 最新状态)
        timeFinder = TimeFinder.getInstance();
        timeFinder.start();
        System.out.println("TimeFinder started, isRunning=" + timeFinder.isRunning());

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
        beatFinder.addBeatListener(new org.deepsymmetry.beatlink.BeatListener() {
            @Override
            public void newBeat(Beat beat) {
                bpm.set((int) beat.getBpm());
                // 记录当前发 beat 的设备（用于 fallback）
                String oldSource = activeBeatSource.get();
                activeBeatSource.set("" + beat.getDeviceNumber());
                if (!Objects.equals(oldSource, activeBeatSource.get())) {
                    System.out.println("🎵 [BeatLink] Beat from device #" + beat.getDeviceNumber() +
                        ", activeBeatSource: " + oldSource + " -> " + activeBeatSource.get());
                }
            }
        });

        // Master listener - 从 Beat Link 获取真实 master
        virtualCdj.addMasterListener(new org.deepsymmetry.beatlink.MasterListener() {
            @Override
            public void newBeat(Beat beat) {
                // Ignore - not used for master detection
            }

            @Override
            public void masterChanged(DeviceUpdate update) {
                // 真实 master 来自 Beat Link 协议
                int masterDeviceNum = update.getDeviceNumber();
                masterPlayer.set("" + masterDeviceNum);
                System.out.println("🎚️ [BeatLink] MasterListener.masterChanged: device #" + masterDeviceNum);
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
     * 获取指定播放器的曲目元数据（兼容 dbclient 反射调用）
     */
    public Map<String, Object> getTrackMetadata(int playerNumber) {
        Map<String, Object> out = new HashMap<>();
        PlayerState ps = players.get(playerNumber);
        if (ps == null) {
            out.put("title", null);
            out.put("artist", null);
            out.put("album", null);
            out.put("durationMs", 0L);
            out.put("metadataFound", false);
            out.put("error", "player-not-found");
            return out;
        }

        try {
            TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNumber);
            if (meta == null) {
                meta = ps.trackMeta;
            }
            if (meta != null) {
                out.put("title", meta.getTitle());
                out.put("artist", meta.getArtist() != null ? meta.getArtist().label : null);
                out.put("album", meta.getAlbum() != null ? meta.getAlbum().label : null);
                out.put("durationMs", (long) meta.getDuration() * 1000L);
                out.put("metadataFound", true);
                return out;
            }
        } catch (Exception e) {
            out.put("error", e.getClass().getSimpleName() + ":" + e.getMessage());
        }

        if (ps.trackMeta != null) {
            out.put("title", ps.trackMeta.getTitle());
            out.put("artist", ps.trackMeta.getArtist() != null ? ps.trackMeta.getArtist().label : null);
            out.put("album", ps.trackMeta.getAlbum() != null ? ps.trackMeta.getAlbum().label : null);
            out.put("durationMs", (long) ps.trackMeta.getDuration() * 1000L);
            out.put("metadataFound", true);
        } else {
            out.put("title", null);
            out.put("artist", null);
            out.put("album", null);
            out.put("durationMs", 0L);
            out.put("metadataFound", false);
        }
        return out;
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
            t.put("sourcePlayer", ps.status != null ? ps.status.getTrackSourcePlayer() : null);
            t.put("rekordboxId", ps.status != null ? ps.status.getRekordboxId() : null);

            // metadataFound 以当前查询命中为准（后续会在分支里覆盖）
            t.put("metadataFound", false);

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
                    t.put("durationMs", (long) meta.getDuration() * 1000L);
                    t.put("metadataFound", true);
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

        // realMaster: beat-link 通用方法获取的真实 master
        String realMaster = resolveRealMaster();
        // effectiveSource: 直接使用 realMaster，拿不到时为 null
        String effectiveSource = getEffectiveSource();

        Integer realMasterNum = null;
        Integer effectiveSourceNum = null;
        try {
            if (realMaster != null) realMasterNum = Integer.parseInt(realMaster);
            if (effectiveSource != null) effectiveSourceNum = Integer.parseInt(effectiveSource);
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
            // master 字段只基于真实 master
            p.put("master", realMasterNum != null && playerNum.intValue() == realMasterNum.intValue());
            // effectiveSource 用于业务跟随
            p.put("effectiveSource", effectiveSourceNum != null && playerNum.intValue() == effectiveSourceNum.intValue());
            p.put("activeBeatSource", activeBeatSource.get());  // 当前发 beat 的设备

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

                // 透传 CdjStatus 原始状态字段（用于 CUED 真值验证）
                Map<String, Object> debugState = extractCdjDebugState(ps.status);
                p.put("debugState", debugState);
                // 同步平铺一份，避免前端路径误读导致字段丢失
                p.put("isPlaying", debugState.get("isPlaying"));
                p.put("isCued", debugState.get("isCued"));
                p.put("isPaused", debugState.get("isPaused"));
                p.put("isTrackLoaded", debugState.get("isTrackLoaded"));
                p.put("isAtEnd", debugState.get("isAtEnd"));
                p.put("playState1", debugState.get("playState1"));
                p.put("playState2", debugState.get("playState2"));
                p.put("playState3", debugState.get("playState3"));

                // currentTimeMs: 优先使用 TimeFinder 真实传输时间，其次用 beat grid 推导
                // 时间字段优先级（V1.1 校准版）：
                // 1. TimeFinder.getTimeFor() - 真实传输时间（优先）
                // 2. BeatGrid 推导时间 - 兜底（当 TimeFinder 不可用时）
                Integer currentTimeMs = null;
                String timeSource = "NONE";
                
                // 1. 优先：TimeFinder.getTimeFor() - 真实传输时间
                try {
                    if (timeFinder != null && timeFinder.isRunning()) {
                        Long trueTime = timeFinder.getTimeFor(playerNum);
                        if (trueTime != null && trueTime > 0) {
                            currentTimeMs = trueTime.intValue();
                            p.put("currentTimeMs", currentTimeMs);
                            timeSource = "TimeFinder";
                        }
                    }
                } catch (Exception ignored) {}
                
                // 2. 次选：beat grid 推导时间（fallback）
                if (currentTimeMs == null || currentTimeMs == 0) {
                    try {
                        BeatGrid grid = beatGridFinder.getLatestBeatGridFor(playerNum);
                        if (grid != null) {
                            int beatNum = ps.status.getBeatNumber();
                            if (beatNum > 0 && beatNum <= grid.beatCount) {
                                currentTimeMs = (int) grid.getTimeWithinTrack(beatNum);
                                p.put("currentTimeMs", currentTimeMs);
                                p.put("beatTimeMs", currentTimeMs);
                                if (timeSource.equals("NONE")) {
                                    timeSource = "BeatGrid";
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                
                // 记录时间来源（供调试 UI 使用）
                p.put("timeSource", timeSource);
            } else {
                p.put("playing", false);
                p.put("onAir", false);
                p.put("beat", null);
                p.put("bpm", null);
                p.put("pitch", null);
            }

            // Track metadata
            Map<String, Object> track = new HashMap<>();
            track.put("trackType", ps.status != null && ps.status.getTrackType() != null ? ps.status.getTrackType().toString() : null);
            track.put("sourceSlot", ps.status != null && ps.status.getTrackSourceSlot() != null ? ps.status.getTrackSourceSlot().toString() : null);
            track.put("sourcePlayer", ps.status != null ? ps.status.getTrackSourcePlayer() : null);
            track.put("rekordboxId", ps.status != null ? ps.status.getRekordboxId() : null);
            track.put("metadataFound", false);

            try {
                TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNum);
                if (meta != null) {
                    track.put("title", meta.getTitle());
                    track.put("artist", meta.getArtist() != null ? meta.getArtist().label : null);
                    track.put("album", meta.getAlbum() != null ? meta.getAlbum().label : null);
                    track.put("duration", meta.getDuration());
                    track.put("durationMs", (long) meta.getDuration() * 1000L);
                    track.put("metadataFound", true);

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

            // 统一状态判定（真值字段优先，time+beat 仅兜底）
            Map<String, Object> debugState = (Map<String, Object>) p.get("debugState");
            String normalizedState = resolvePlayerState(p, debugState, track);
            p.put("state", normalizedState);

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
                    List<Integer> beatTicksBpmX100 = new ArrayList<>(grid.beatCount);
                    int maxBeats = Math.min(grid.beatCount, 2048);
                    for (int bi = 1; bi <= maxBeats; bi++) {
                        beatTicksMs.add((int) grid.getTimeWithinTrack(bi));
                        beatTicksInBar.add(grid.getBeatWithinBar(bi));
                        beatTicksBpmX100.add(grid.getBpm(bi));
                    }
                    analysis.put("beatTicksMs", beatTicksMs);
                    analysis.put("beatTicksInBar", beatTicksInBar);
                    analysis.put("beatTicksBpmX100", beatTicksBpmX100);
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

            // CueList raw tags for desktop bridge -> CueList(List<ByteBuffer>, List<ByteBuffer>)
            try {
                TrackMetadata meta = metadataFinder.getLatestMetadataFor(playerNum);
                CueList cueListObj = (meta != null) ? meta.getCueList() : null;
                if (cueListObj != null) {
                    java.util.List<String> cueRawTagsBase64 = new java.util.ArrayList<>();
                    if (cueListObj.rawTags != null) {
                        for (java.nio.ByteBuffer b : cueListObj.rawTags) {
                            if (b == null) continue;
                            java.nio.ByteBuffer ro = b.asReadOnlyBuffer();
                            ro.rewind();
                            byte[] arr = new byte[ro.remaining()];
                            ro.get(arr);
                            cueRawTagsBase64.add(java.util.Base64.getEncoder().encodeToString(arr));
                        }
                    }
                    java.util.List<String> cueRawExtendedTagsBase64 = new java.util.ArrayList<>();
                    if (cueListObj.rawExtendedTags != null) {
                        for (java.nio.ByteBuffer b : cueListObj.rawExtendedTags) {
                            if (b == null) continue;
                            java.nio.ByteBuffer ro = b.asReadOnlyBuffer();
                            ro.rewind();
                            byte[] arr = new byte[ro.remaining()];
                            ro.get(arr);
                            cueRawExtendedTagsBase64.add(java.util.Base64.getEncoder().encodeToString(arr));
                        }
                    }
                    analysis.put("cueRawTagsBase64", cueRawTagsBase64);
                    analysis.put("cueRawExtendedTagsBase64", cueRawExtendedTagsBase64);
                }
            } catch (Exception ignore) {
                // keep cue summary fields even if raw tag export fails
            }

            // Waveform output (beat-link native raw only)
            try {
                WaveformPreview preview = waveformFinder.getLatestPreviewFor(playerNum);
                analysis.put("previewWaveformFound", preview != null);
                analysis.put("previewLength", preview != null ? preview.segmentCount : 0);
                if (preview != null) {
                    try {
                        java.nio.ByteBuffer buf = preview.getData().asReadOnlyBuffer();
                        buf.rewind();
                        byte[] raw = new byte[buf.remaining()];
                        buf.get(raw);
                        analysis.put("previewRawBase64", java.util.Base64.getEncoder().encodeToString(raw));
                        analysis.put("previewRawIsColor", preview.isColor);
                        analysis.put("previewRawStyle", preview.style.toString());
                        analysis.put("previewRawFormat", "beatlink.getData.v1");
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                analysis.put("previewWaveformFound", false);
                analysis.put("previewLength", 0);
            }

            try {
                WaveformDetail detail = waveformFinder.getLatestDetailFor(playerNum);
                analysis.put("detailedWaveformFound", detail != null);
                analysis.put("detailLength", detail != null ? detail.getFrameCount() : 0);
                if (detail != null) {
                    try {
                        java.nio.ByteBuffer buf = detail.getData().asReadOnlyBuffer();
                        buf.rewind();
                        byte[] raw = new byte[buf.remaining()];
                        buf.get(raw);
                        analysis.put("detailRawBase64", java.util.Base64.getEncoder().encodeToString(raw));
                        analysis.put("detailRawIsColor", detail.isColor);
                        analysis.put("detailRawStyle", detail.style.toString());
                        analysis.put("detailRawFormat", "beatlink.getData.v1");
                    } catch (Exception ignore) {}
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
        result.put("master", masterPlayer.get());  // 真实 master
        result.put("effectiveSource", getEffectiveSource());  // 实际使用源
        result.put("activeBeatSource", activeBeatSource.get());
        result.put("updatedAt", now);
        result.put("ruleVersion", "sections-mvp-v2");

        return result;
    }

    /**
     * Unified player state - neutral endpoint for AI/rule/trigger systems
     */
    private Map<String, Object> extractCdjDebugState(CdjStatus status) {
        Map<String, Object> d = new HashMap<>();
        Map<String, Object> errors = new HashMap<>();
        d.put("statusClass", status != null ? status.getClass().getName() : "null");

        if (status == null) {
            d.put("isPlaying", "N/A");
            d.put("isCued", "N/A");
            d.put("isPaused", "N/A");
            d.put("isTrackLoaded", "N/A");
            d.put("isAtEnd", "N/A");
            d.put("playState1", "N/A");
            d.put("playState2", "N/A");
            d.put("playState3", "N/A");
            d.put("errors", errors);
            return d;
        }

        try { d.put("isPlaying", status.isPlaying()); } catch (Exception e) { d.put("isPlaying", "N/A"); errors.put("isPlaying", e.getClass().getSimpleName() + ":" + e.getMessage()); }
        try { d.put("isCued", status.isCued()); } catch (Exception e) { d.put("isCued", "N/A"); errors.put("isCued", e.getClass().getSimpleName() + ":" + e.getMessage()); }
        try { d.put("isPaused", status.isPaused()); } catch (Exception e) { d.put("isPaused", "N/A"); errors.put("isPaused", e.getClass().getSimpleName() + ":" + e.getMessage()); }
        try { d.put("isTrackLoaded", status.isTrackLoaded()); } catch (Exception e) { d.put("isTrackLoaded", "N/A"); errors.put("isTrackLoaded", e.getClass().getSimpleName() + ":" + e.getMessage()); }
        try { d.put("isAtEnd", status.isAtEnd()); } catch (Exception e) { d.put("isAtEnd", "N/A"); errors.put("isAtEnd", e.getClass().getSimpleName() + ":" + e.getMessage()); }

        try { d.put("playState1", String.valueOf(status.getPlayState1())); } catch (Exception e) { d.put("playState1", "N/A"); errors.put("playState1", e.getClass().getSimpleName() + ":" + e.getMessage()); }
        try { d.put("playState2", String.valueOf(status.getPlayState2())); } catch (Exception e) { d.put("playState2", "N/A"); errors.put("playState2", e.getClass().getSimpleName() + ":" + e.getMessage()); }
        try { d.put("playState3", String.valueOf(status.getPlayState3())); } catch (Exception e) { d.put("playState3", "N/A"); errors.put("playState3", e.getClass().getSimpleName() + ":" + e.getMessage()); }

        d.put("errors", errors);
        return d;
    }

    private Boolean boolFrom(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return null;
    }

    private String resolvePlayerState(Map<String, Object> player, Map<String, Object> debugState, Map<String, Object> track) {
        boolean online = Boolean.TRUE.equals(player.get("active"));
        if (!online) return "OFFLINE";

        Boolean isPlaying = boolFrom(debugState, "isPlaying");
        Boolean isCued = boolFrom(debugState, "isCued");
        Boolean isPaused = boolFrom(debugState, "isPaused");
        Boolean isTrackLoaded = boolFrom(debugState, "isTrackLoaded");

        // 真值字段优先级：
        // 1. isPlaying
        // 2. isCued
        // 3. isPaused && !isCued
        // 4. isTrackLoaded == false
        if (Boolean.TRUE.equals(isPlaying)) return "PLAYING";
        if (Boolean.TRUE.equals(isCued)) return "CUED";
        if (Boolean.TRUE.equals(isPaused) && !Boolean.TRUE.equals(isCued)) return "PAUSED";
        if (Boolean.FALSE.equals(isTrackLoaded)) return "STOPPED";

        // 兜底：仅当真值字段不可用/异常时使用 time+beat
        Number beat = (Number) player.get("beat");
        Number timeMs = (Number) player.get("currentTimeMs");
        double nowMs = timeMs != null ? timeMs.doubleValue() : 0;
        double beatNum = beat != null ? beat.doubleValue() : 0;

        boolean hasTrack = false;
        if (track != null) {
            Object title = track.get("title");
            hasTrack = title instanceof String && !((String) title).trim().isEmpty();
        }
        boolean hasPosition = beatNum > 0 || nowMs > 100;

        if (hasTrack && nowMs <= 100 && beatNum <= 0) return "CUED";
        if (hasTrack && hasPosition) return "PAUSED";
        return "STOPPED";
    }

    public Map<String, Object> getPlayersState() {
        // 复用 getAiPlayers，它已经统一了 master/effectiveSource/activeBeatSource 逻辑
        Map<String, Object> result = getAiPlayers();

        // Transform summary to decision and add trigger-friendly fields
        List<Map<String, Object>> players = (List<Map<String, Object>>) result.get("players");

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

            // master 字段已在 getAiPlayers 中正确设置（基于真实 master），无需重复设置

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
