package dbclient.media.library;

import dbclient.media.model.TrackLibraryEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 曲目库统一服务入口
 * 
 * 职责边界：
 * - 负责曲目资产的统一入口（导入、查询、删除）
 * - 协调 TrackLibraryRepository 与 MarkerRepository
 * - 不直接处理播放逻辑（由 BasicLocalPlaybackEngine 处理）
 * - 不直接处理分析任务（由 AnalysisService 处理）
 * 
 * MVP 约束：
 * - filePath 唯一性：重复导入返回已有记录
 * - trackId 与 entryId 使用相同 UUID（MVP）
 */
public class TrackLibraryService {

    private final TrackLibraryRepository trackRepo;
    private final MarkerRepository markerRepo;

    public TrackLibraryService() {
        this(new JsonFileTrackLibraryRepository(), new InMemoryMarkerRepository());
    }

    public TrackLibraryService(TrackLibraryRepository trackRepo, MarkerRepository markerRepo) {
        this.trackRepo = trackRepo;
        this.markerRepo = markerRepo;
    }

    /**
     * 导入曲目（创建资产记录）
     * 
     * MVP 策略：
     * - 以 filePath 判重，重复导入返回已有记录
     * - trackId = entryId（使用相同 UUID）
     * 
     * @param filePath 文件路径
     * @param title 标题
     * @param artist 艺术家
     * @param durationMs 时长
     * @param sampleRate 采样率
     * @param channels 声道数
     * @return 曲目资产（已有或新建）
     */
    public TrackLibraryEntry importTrack(String filePath, String title, String artist, 
            long durationMs, int sampleRate, int channels) {
        
        // 检查 filePath 是否已存在（MVP 唯一性策略）
        Optional<TrackLibraryEntry> existing = trackRepo.findByFilePath(filePath);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 创建新资产记录
        String entryId = UUID.randomUUID().toString();
        TrackLibraryEntry entry = TrackLibraryEntry.builder()
            .entryId(entryId)
            .trackId(entryId)  // MVP: trackId = entryId
            .filePath(filePath)
            .title(title)
            .artist(artist)
            .durationMs(durationMs)
            .sampleRate(sampleRate)
            .channels(channels)
            .build();

        return trackRepo.save(entry);
    }

    /**
     * 根据 entryId 查询
     */
    public Optional<TrackLibraryEntry> findByEntryId(String entryId) {
        return trackRepo.findByEntryId(entryId);
    }

    /**
     * 根据 trackId 查询
     */
    public Optional<TrackLibraryEntry> findByTrackId(String trackId) {
        return trackRepo.findByTrackId(trackId);
    }

    /**
     * 根据 filePath 查询
     */
    public Optional<TrackLibraryEntry> findByFilePath(String filePath) {
        return trackRepo.findByFilePath(filePath);
    }

    /**
     * 查询所有曲目资产
     */
    public List<TrackLibraryEntry> findAll() {
        return trackRepo.findAll();
    }

    /**
     * 删除曲目资产（同时删除关联的 Markers）
     */
    public boolean deleteTrack(String entryId) {
        Optional<TrackLibraryEntry> entry = trackRepo.findByEntryId(entryId);
        if (entry.isEmpty()) {
            return false;
        }

        // 删除关联的 Markers
        markerRepo.deleteByTrackId(entry.get().getTrackId());

        // 删除资产记录
        trackRepo.delete(entryId);
        return true;
    }

    /**
     * 检查 filePath 是否已存在
     */
    public boolean existsByFilePath(String filePath) {
        return trackRepo.existsByFilePath(filePath);
    }

    /**
     * 更新曲目资产
     */
    public TrackLibraryEntry updateEntry(TrackLibraryEntry entry) {
        entry.touch();
        return trackRepo.save(entry);
    }

    // ==================== Marker 便捷方法 ====================

    /**
     * 添加 Marker
     */
    public TrackLibraryEntry addMarker(String trackId, dbclient.media.model.MarkerPoint marker) {
        marker.setTrackId(trackId);
        markerRepo.save(marker);

        // 更新 entry 的 markers
        Optional<TrackLibraryEntry> entry = trackRepo.findByTrackId(trackId);
        if (entry.isPresent()) {
            entry.get().addMarker(marker);
            trackRepo.save(entry.get());
        }

        return entry.orElse(null);
    }

    /**
     * 查询 Markers
     */
    public List<dbclient.media.model.MarkerPoint> getMarkers(String trackId) {
        return markerRepo.findByTrackId(trackId);
    }

    /**
     * 查询启用的 Markers
     */
    public List<dbclient.media.model.MarkerPoint> getEnabledMarkers(String trackId) {
        return markerRepo.findEnabledByTrackId(trackId);
    }

    /**
     * 更新 Marker
     */
    public dbclient.media.model.MarkerPoint updateMarker(dbclient.media.model.MarkerPoint marker) {
        marker.touch();
        return markerRepo.save(marker);
    }

    /**
     * 删除 Marker
     */
    public boolean deleteMarker(String markerId) {
        Optional<dbclient.media.model.MarkerPoint> marker = markerRepo.findById(markerId);
        if (marker.isEmpty()) {
            return false;
        }

        // 从 entry 中移除
        String trackId = marker.get().getTrackId();
        Optional<TrackLibraryEntry> entry = trackRepo.findByTrackId(trackId);
        if (entry.isPresent()) {
            entry.get().removeMarker(markerId);
            trackRepo.save(entry.get());
        }

        markerRepo.delete(markerId);
        return true;
    }

    // ==================== 依赖获取（供外部注入/使用） ====================

    public TrackLibraryRepository getTrackRepository() {
        return trackRepo;
    }

    public MarkerRepository getMarkerRepository() {
        return markerRepo;
    }
}
