package dbclient.media.library;

import dbclient.media.model.MarkerPoint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Marker 仓储内存实现
 * 
 * 设计约束：
 * - id 由模型层默认生成（UUID），这里不做覆盖
 * - enabled 默认 true（在模型层保证）
 * - timestamps 由 Service 层调用 marker.touch() 维护
 * 
 * 存储结构：
 * - markerMap: markerId -> MarkerPoint
 * - trackIndex: trackId -> Set<markerId>（反向索引）
 */
public class InMemoryMarkerRepository implements MarkerRepository {

    private final Map<String, MarkerPoint> markerMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> trackIndex = new ConcurrentHashMap<>();

    @Override
    public MarkerPoint save(MarkerPoint marker) {
        if (marker == null || marker.getId() == null) {
            throw new IllegalArgumentException("Marker and marker.id cannot be null");
        }

        // 更新索引
        if (marker.getTrackId() != null) {
            trackIndex.computeIfAbsent(marker.getTrackId(), k -> ConcurrentHashMap.newKeySet())
                      .add(marker.getId());
        }

        markerMap.put(marker.getId(), marker);
        return marker;
    }

    @Override
    public Optional<MarkerPoint> findById(String markerId) {
        if (markerId == null) return Optional.empty();
        return Optional.ofNullable(markerMap.get(markerId));
    }

    @Override
    public List<MarkerPoint> findByTrackId(String trackId) {
        if (trackId == null) return Collections.emptyList();
        
        Set<String> markerIds = trackIndex.get(trackId);
        if (markerIds == null || markerIds.isEmpty()) {
            return Collections.emptyList();
        }

        return markerIds.stream()
                .map(markerMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<MarkerPoint> findEnabledByTrackId(String trackId) {
        return findByTrackId(trackId).stream()
                .filter(MarkerPoint::isEnabled)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String markerId) {
        if (markerId == null) return;

        MarkerPoint removed = markerMap.remove(markerId);
        if (removed != null && removed.getTrackId() != null) {
            Set<String> ids = trackIndex.get(removed.getTrackId());
            if (ids != null) {
                ids.remove(markerId);
                if (ids.isEmpty()) {
                    trackIndex.remove(removed.getTrackId());
                }
            }
        }
    }

    @Override
    public void deleteByTrackId(String trackId) {
        if (trackId == null) return;

        Set<String> markerIds = trackIndex.remove(trackId);
        if (markerIds != null) {
            for (String id : markerIds) {
                markerMap.remove(id);
            }
        }
    }

    @Override
    public boolean exists(String markerId) {
        if (markerId == null) return false;
        return markerMap.containsKey(markerId);
    }

    /**
     * 清空所有数据（测试用）
     */
    public void clear() {
        markerMap.clear();
        trackIndex.clear();
    }

    /**
     * 获取统计信息（调试用）
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalMarkers", markerMap.size());
        stats.put("totalTracks", trackIndex.size());
        return stats;
    }
}
