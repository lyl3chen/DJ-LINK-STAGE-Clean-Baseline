package dbclient.media.library;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.TrackInfo;
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
     * 保存分析结果
     *
     * @param trackId 曲目 ID
     * @param result 分析结果
     */
    void saveAnalysis(String trackId, AnalysisResult result);

    /**
     * 获取分析结果
     *
     * @param trackId 曲目 ID
     * @return 分析结果（Optional）
     */
    Optional<AnalysisResult> getAnalysis(String trackId);
}
