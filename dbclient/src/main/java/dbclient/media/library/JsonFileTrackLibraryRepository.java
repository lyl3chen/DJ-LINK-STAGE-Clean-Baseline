package dbclient.media.library;

import dbclient.media.model.TrackLibraryEntry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 曲目库资产仓储 JSON 文件实现
 * 
 * 存储策略：
 * - 文件路径: ~/.dj-link-stage/library/library.json
 * - 格式: JSON 数组，存储 List<TrackLibraryEntry>
 * - 读取: 构造函数中同步加载
 * - 写入: 每次数据变更后异步写入（避免阻塞主流程）
 * - 异常恢复: 读取失败时回退到空列表；写入失败记录日志
 * 
 * 索引结构（内存中维护，不持久化）：
 * - entryIndex: entryId -> TrackLibraryEntry
 * - trackIndex: trackId -> entryId
 * - filePathIndex: filePath -> entryId
 */
public class JsonFileTrackLibraryRepository implements TrackLibraryRepository {

    private static final String STORAGE_DIR = System.getProperty("user.home") + "/.dj-link-stage/library";
    private static final String STORAGE_FILE = STORAGE_DIR + "/library.json";
    
    private final Map<String, TrackLibraryEntry> entryIndex = new ConcurrentHashMap<>();
    private final Map<String, String> trackIndex = new ConcurrentHashMap<>();
    private final Map<String, String> filePathIndex = new ConcurrentHashMap<>();
    
    private final Gson gson;
    private final ExecutorService writeExecutor;
    private volatile boolean dirty = false;

    public JsonFileTrackLibraryRepository() {
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "library-storage-writer");
            t.setDaemon(true);
            return t;
        });
        
        // 启动时加载数据
        loadFromFile();
    }

    /**
     * 从 JSON 文件加载数据
     */
    private void loadFromFile() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            System.out.println("[TrackLibrary] Storage file not found, starting with empty library: " + STORAGE_FILE);
            return;
        }

        try (Reader reader = new FileReader(file)) {
            Type listType = new TypeToken<List<TrackLibraryEntry>>(){}.getType();
            List<TrackLibraryEntry> entries = gson.fromJson(reader, listType);
            
            if (entries != null) {
                for (TrackLibraryEntry entry : entries) {
                    if (entry != null && entry.getEntryId() != null) {
                        entryIndex.put(entry.getEntryId(), entry);
                        if (entry.getTrackId() != null) {
                            trackIndex.put(entry.getTrackId(), entry.getEntryId());
                        }
                        if (entry.getFilePath() != null) {
                            filePathIndex.put(entry.getFilePath(), entry.getEntryId());
                        }
                    }
                }
                System.out.println("[TrackLibrary] Loaded " + entryIndex.size() + " entries from " + STORAGE_FILE);
            }
        } catch (Exception e) {
            System.err.println("[TrackLibrary] Failed to load storage file, starting with empty library: " + e.getMessage());
            // 读取失败时回退到空列表
        }
    }

    /**
     * 异步写入 JSON 文件
     */
    private void scheduleWrite() {
        dirty = true;
        writeExecutor.submit(this::writeToFile);
    }

    /**
     * 同步写入文件（在后台线程中执行）
     */
    private void writeToFile() {
        if (!dirty) {
            return;
        }
        dirty = false;

        try {
            // 确保目录存在
            Path dir = Paths.get(STORAGE_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // 写入临时文件，然后原子重命名
            File tempFile = new File(STORAGE_FILE + ".tmp");
            List<TrackLibraryEntry> entries = new ArrayList<>(entryIndex.values());
            
            try (Writer writer = new FileWriter(tempFile)) {
                gson.toJson(entries, writer);
            }

            // 原子重命名
            File targetFile = new File(STORAGE_FILE);
            if (targetFile.exists()) {
                targetFile.delete();
            }
            tempFile.renameTo(targetFile);
            
            System.out.println("[TrackLibrary] Saved " + entries.size() + " entries to " + STORAGE_FILE);
        } catch (Exception e) {
            System.err.println("[TrackLibrary] Failed to write storage file: " + e.getMessage());
            e.printStackTrace();
            // 写入失败不中断流程，下次变更时重试
            dirty = true;
        }
    }

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
        } else {
            // 更新：清理旧索引
            TrackLibraryEntry oldEntry = entryIndex.get(oldEntryId);
            if (oldEntry != null) {
                if (oldEntry.getTrackId() != null) {
                    trackIndex.remove(oldEntry.getTrackId());
                }
                if (oldEntry.getFilePath() != null) {
                    filePathIndex.remove(oldEntry.getFilePath());
                }
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

        // 异步写入文件
        scheduleWrite();

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
            
            // 异步写入文件
            scheduleWrite();
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
        scheduleWrite();
    }

    /**
     * 获取统计信息（调试用）
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEntries", entryIndex.size());
        stats.put("trackIndexSize", trackIndex.size());
        stats.put("filePathIndexSize", filePathIndex.size());
        stats.put("storageFile", STORAGE_FILE);
        return stats;
    }
    
    /**
     * 强制同步写入（用于优雅关闭）
     */
    public void flush() {
        writeToFile();
    }
}
