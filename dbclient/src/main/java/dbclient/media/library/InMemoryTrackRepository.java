package dbclient.media.library;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.TrackInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版曲目仓储实现
 * 简单优先，后续可平滑替换为 JsonTrackRepository 或 SqliteTrackRepository
 */
public class InMemoryTrackRepository implements TrackRepository {

    private final Map<String, TrackInfo> tracks = new ConcurrentHashMap<>();
    private final Map<String, AnalysisResult> analyses = new ConcurrentHashMap<>();

    @Override
    public TrackInfo save(TrackInfo track) {
        if (track.getTrackId() == null || track.getTrackId().isEmpty()) {
            track.setTrackId(UUID.randomUUID().toString());
        }
        tracks.put(track.getTrackId(), track);
        return track;
    }

    @Override
    public Optional<TrackInfo> findById(String trackId) {
        return Optional.ofNullable(tracks.get(trackId));
    }

    @Override
    public List<TrackInfo> findAll() {
        return new ArrayList<>(tracks.values());
    }

    @Override
    public List<TrackInfo> findByAnalysisStatus(AnalysisStatus status) {
        List<TrackInfo> result = new ArrayList<>();
        for (TrackInfo track : tracks.values()) {
            AnalysisResult analysis = analyses.get(track.getTrackId());
            if (analysis != null && analysis.getAnalysisStatus() == status) {
                result.add(track);
            } else if (analysis == null && status == AnalysisStatus.PENDING) {
                result.add(track);
            }
        }
        return result;
    }

    @Override
    public void delete(String trackId) {
        tracks.remove(trackId);
        analyses.remove(trackId);
    }

    @Override
    public Optional<TrackInfo> findByFilePath(String filePath) {
        return tracks.values().stream()
            .filter(t -> filePath.equals(t.getFilePath()))
            .findFirst();
    }

    @Override
    public boolean exists(String trackId) {
        return tracks.containsKey(trackId);
    }

    @Override
    public void saveAnalysis(String trackId, AnalysisResult result) {
        analyses.put(trackId, result);
    }

    @Override
    public Optional<AnalysisResult> getAnalysis(String trackId) {
        return Optional.ofNullable(analyses.get(trackId));
    }

    /**
     * 清空所有数据（测试用）
     */
    public void clear() {
        tracks.clear();
        analyses.clear();
    }

    /**
     * 获取分析结果数量
     */
    public int getAnalysisCount() {
        return analyses.size();
    }
}
