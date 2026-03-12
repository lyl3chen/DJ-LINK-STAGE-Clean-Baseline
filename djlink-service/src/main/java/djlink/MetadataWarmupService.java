package djlink;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.data.DataReference;
import org.deepsymmetry.beatlink.data.MetadataFinder;
import org.deepsymmetry.beatlink.data.TrackMetadata;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 预热元数据缓存（轻量版）
 *
 * 目标：
 * 1) 在播放器状态更新时，提前异步请求当前曲目的 metadata；
 * 2) 当 MetadataFinder 的 latest cache 暂时 miss 时，提供一个本地兜底缓存；
 * 3) 不改现有 beat-link 主链路，仅做“命中率增强层”。
 */
public class MetadataWarmupService {
    private final Map<String, TrackMetadata> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "metadata-warmup");
        t.setDaemon(true);
        return t;
    });

    public void prefetchFromStatus(CdjStatus status, MetadataFinder finder) {
        if (status == null || finder == null) return;
        int sourcePlayer = status.getTrackSourcePlayer();
        CdjStatus.TrackSourceSlot slot = status.getTrackSourceSlot();
        int rekordboxId = status.getRekordboxId();
        CdjStatus.TrackType trackType = status.getTrackType();
        if (sourcePlayer <= 0 || slot == null || rekordboxId <= 0) return;

        DataReference ref = new DataReference(sourcePlayer, slot, rekordboxId, trackType);
        String key = key(ref);
        if (cache.containsKey(key)) return;

        executor.submit(() -> {
            try {
                TrackMetadata meta = finder.requestMetadataFrom(ref, trackType);
                if (meta != null) cache.put(key, meta);
            } catch (Exception ignored) {
                // 预热失败不影响主链路。
            }
        });
    }

    public TrackMetadata getFromStatus(CdjStatus status) {
        if (status == null) return null;
        int sourcePlayer = status.getTrackSourcePlayer();
        CdjStatus.TrackSourceSlot slot = status.getTrackSourceSlot();
        int rekordboxId = status.getRekordboxId();
        CdjStatus.TrackType trackType = status.getTrackType();
        if (sourcePlayer <= 0 || slot == null || rekordboxId <= 0) return null;
        return cache.get(key(new DataReference(sourcePlayer, slot, rekordboxId, trackType)));
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static String key(DataReference ref) {
        return ref.player + "|" + ref.slot + "|" + ref.rekordboxId + "|" + ref.trackType;
    }
}
