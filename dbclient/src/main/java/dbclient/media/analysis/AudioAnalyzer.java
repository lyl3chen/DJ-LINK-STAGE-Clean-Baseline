package dbclient.media.analysis;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.TrackInfo;

/**
 * 音频分析器接口
 * 允许替换不同分析库实现（TarsosDSP、Essentia 等）
 * 接口只使用标准 JDK 类型和我们的 DTO，不暴露开源库类型
 */
public interface AudioAnalyzer {

    /**
     * 分析音频文件
     *
     * @param track 曲目信息（包含 filePath 等）
     * @return AnalysisResult 分析结果（我们的 DTO）
     */
    AnalysisResult analyze(TrackInfo track);

    /**
     * 获取分析器名称
     */
    String getAnalyzerName();

    /**
     * 是否支持该文件格式
     */
    boolean supportsFormat(String fileExtension);
}
