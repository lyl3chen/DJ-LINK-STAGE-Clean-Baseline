package dbclient.media.library;

import dbclient.media.model.TrackInfo;
import dbclient.media.model.AnalysisStatus;
import java.util.List;
import java.util.Optional;

/**
 * 曲目仓储接口
 * 允许替换不同存储实现（JSON文件、SQLite、数据库等）
 * 接口只使用标准 JDK 类型和我们的 DTO，不暴露开源库类型
 */
public interface TrackRepository {

    /**
     * 保存曲目信息
     *
     * @param track 曲目信息
     * @return 保存后的曲目（可能包含生成的 ID）
     */
    TrackInfo save(TrackInfo track);

    /**
     * 根据 ID 查询曲目
     *
     * @param trackId 曲目 ID
     * @return 曲目信息（Optional）
     */
    Optional<TrackInfo> findById(String trackId);

    /**
     * 查询所有曲目
     */
    List<TrackInfo> findAll();

    /**
     * 根据分析状态查询曲目
     *
     * @param status 分析状态
     */
    List<TrackInfo> findByAnalysisStatus(AnalysisStatus status);

    /**
     * 查询未分析的曲目
     */
    default List<TrackInfo> findUnanalyzed() {
        return findByAnalysisStatus(AnalysisStatus.PENDING);
    }

    /**
     * 删除曲目
     *
     * @param trackId 曲目 ID
     */
    void delete(String trackId);

    /**
     * 根据文件路径查询曲目
     *
     * @param filePath 文件路径
     */
    Optional<TrackInfo> findByFilePath(String filePath);

    /**
     * 检查曲目是否存在
     *
     * @param trackId 曲目 ID
     */
    boolean exists(String trackId);

    /**
     * 更新曲目分析状态
     *
     * @param trackId 曲目 ID
     * @param status 新状态
     */
    void updateAnalysisStatus(String trackId, AnalysisStatus status);
}
