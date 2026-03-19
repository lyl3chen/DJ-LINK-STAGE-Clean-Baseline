package dbclient.media.library;

import dbclient.media.model.TrackLibraryEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 曲目库资产仓储内存实现
 * 
 * 存储结构：
 * - entryIndex: entryId -> TrackLibraryEntry
 * - trackIndex: trackId -> entryId（反向索引，支持 trackId 查询）
 * - filePathIndex: filePath -> entryId（反向索引，支持 filePath 查询）
 * 
 * 设计约束：
 * - entryId / trackId: MVP 阶段使用相同 UUID
 * - filePath 唯一性: 以 filePath 为主要唯一键
 * - id 生成: 由 Service 层生成后传入，Repository 不负责生成
 */
public class InMemoryTrackLibraryRepository implements TrackLibraryRepository {

    private final Map<String, TrackLibraryEntry> entryIndex = new ConcurrentHashMap<>();
    private final Map<String, String> trackIndex = new ConcurrentHashMap<>();
    private final Map<String, String> filePathIndex = new ConcurrentHashMap<>();

    @Override
    public TrackLibraryEntry save(TrackLibraryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Entry cannot be null");
        }
        if (entry.getEntryId() == null || entry.getEntryId().isEmpty()) {
            throw new IllegalArgumentException("Entry.entryId cannot be null or empty");
        }

        // 清理旧索引（如果更新已有记录）
        String oldEntryId = entryIndex.containsKey(entry.getEntryId()) 
            ? entry.getEntryId() 
            : null;
        
        if (oldEntryId == null) {
            // 新增：检查 filePath 冲突
            if (entry.getFilePath() != null && filePathIndex.containsKey(entry.getFilePath())) {
                throw new IllegalStateException("File path already exists: " + entry.getFilePath() + 
                    ". Use findByFilePath() to get existing entry.");
            }
        }

        // 更新索引
        entryIndex.put(entry.getEntryId(), entry);
        
        if (entry.getTrackId() != null) {
            trackIndex.put(entry.getTrackId(), entry.getEntryId());
        }
        
        if (entry.getFilePath() != null) {
            filePathIndex.put(entry.getFilePath(), entry.getEntryId());
        }

        return entry;
    }

    @Override
    public Optional<TrackLibraryEntry> findByEntryId(String entryId) {
        if (entryId == null) return Optional.empty();
        return Optional.ofNullable(entryIndex.get(entryId));
    }

    @Override
    public Optional<TrackLibraryEntry> findByTrackId(String trackId) {
        if (trackId == null) return Optional.empty();
        
        String entryId = trackIndex.get(trackId);
        if (entryId == null) return Optional.empty();
        
        return Optional.ofNullable(entryIndex.get(entryId));
    }

    @Override
    public Optional<TrackLibraryEntry> findByFilePath(String filePath) {
        if (filePath == null) return Optional.empty();
        
        String entryId = filePathIndex.get(filePath);
        if (entryId == null) return Optional.empty();
        
        return Optional.ofNullable(entryIndex.get(entryId));
    }

    @Override
    public List<TrackLibraryEntry> findAll() {
        return new ArrayList<>(entryIndex.values());
    }

    @Override
    public void delete(String entryId) {
        if (entryId == null) return;

        TrackLibraryEntry removed = entryIndex.remove(entryId);
        if (removed != null) {
            // 清理索引
            if (removed.getTrackId() != null) {
                trackIndex.remove(removed.getTrackId());
            }
            if (removed.getFilePath() != null) {
                filePathIndex.remove(removed.getFilePath());
            }
        }
    }

    @Override
    public boolean exists(String entryId) {
        if (entryId == null) return false;
        return entryIndex.containsKey(entryId);
    }

    @Override
    public boolean existsByFilePath(String filePath) {
        if (filePath == null) return false;
        return filePathIndex.containsKey(filePath);
    }

    /**
     * 清空所有数据（测试用）
     */
    public void clear() {
        entryIndex.clear();
        trackIndex.clear();
        filePathIndex.clear();
    }

    /**
     * 获取统计信息（调试用）
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEntries", entryIndex.size());
        stats.put("trackIndexSize", trackIndex.size());
        stats.put("filePathIndexSize", filePathIndex.size());
        return stats;
    }
}
