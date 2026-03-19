package dbclient.media.library;

import dbclient.media.model.TrackLibraryEntry;

import java.util.List;
import java.util.Optional;

/**
 * 曲目库资产仓储接口
 * 
 * 设计约束：
 * - entryId: 资产库记录主键（持久化存储用），Repository 层不生成，由 Service 层生成后传入
 * - trackId: 对上层统一使用的曲目标识，MVP 阶段与 entryId 相同
 * - filePath 唯一性: 以 filePath 为主要唯一键，重复导入返回已有记录
 * - fileHash: 作为辅助校验字段（可选）
 */
public interface TrackLibraryRepository {

    /**
     * 保存曲目资产
     * 
     * @param entry 曲目资产条目
     * @return 保存后的条目
     */
    TrackLibraryEntry save(TrackLibraryEntry entry);

    /**
     * 根据 entryId 查询
     * 
     * @param entryId 资产 ID
     * @return 曲目资产（Optional）
     */
    Optional<TrackLibraryEntry> findByEntryId(String entryId);

    /**
     * 根据 trackId 查询
     * 
     * @param trackId 曲目 ID
     * @return 曲目资产（Optional）
     */
    Optional<TrackLibraryEntry> findByTrackId(String trackId);

    /**
     * 根据 filePath 查询（唯一性策略：filePath 重复返回已有记录）
     * 
     * @param filePath 文件路径
     * @return 曲目资产（Optional）
     */
    Optional<TrackLibraryEntry> findByFilePath(String filePath);

    /**
     * 查询所有曲目资产
     */
    List<TrackLibraryEntry> findAll();

    /**
     * 删除曲目资产
     * 
     * @param entryId 资产 ID
     */
    void delete(String entryId);

    /**
     * 检查资产是否存在
     * 
     * @param entryId 资产 ID
     */
    boolean exists(String entryId);

    /**
     * 检查 filePath 是否已存在
     * 
     * @param filePath 文件路径
     */
    boolean existsByFilePath(String filePath);
}
