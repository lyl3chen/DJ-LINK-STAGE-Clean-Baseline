package dbclient.media.library;

import dbclient.media.model.MarkerPoint;
import java.util.List;
import java.util.Optional;

/**
 * Marker 仓储接口
 * 
 * 设计约束：
 * - id 由模型层默认生成（UUID），Repository 不负责生成
 * - enabled 默认 true
 * - timestamps 由 Service 层调用 marker.touch() 维护
 */
public interface MarkerRepository {

    /**
     * 保存 Marker
     * 
     * @param marker Marker 点
     * @return 保存后的 Marker
     */
    MarkerPoint save(MarkerPoint marker);

    /**
     * 根据 ID 查询 Marker
     * 
     * @param markerId Marker ID
     * @return Marker（Optional）
     */
    Optional<MarkerPoint> findById(String markerId);

    /**
     * 根据 trackId 查询所有 Markers
     * 
     * @param trackId 曲目 ID
     * @return Marker 列表
     */
    List<MarkerPoint> findByTrackId(String trackId);

    /**
     * 根据 trackId 查询启用的 Markers
     * 
     * @param trackId 曲目 ID
     * @return 启用的 Marker 列表
     */
    List<MarkerPoint> findEnabledByTrackId(String trackId);

    /**
     * 删除 Marker
     * 
     * @param markerId Marker ID
     */
    void delete(String markerId);

    /**
     * 根据 trackId 删除所有 Markers
     * 
     * @param trackId 曲目 ID
     */
    void deleteByTrackId(String trackId);

    /**
     * 检查 Marker 是否存在
     * 
     * @param markerId Marker ID
     */
    boolean exists(String markerId);
}
