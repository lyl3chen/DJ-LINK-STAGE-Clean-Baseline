package dbclient.media.analysis;

import dbclient.media.model.AnalysisResult;
import java.util.function.Consumer;

/**
 * 音频分析器接口
 * 允许替换不同分析库实现（TarsosDSP、Essentia 等）
 * 接口只使用标准 JDK 类型，不暴露开源库类型
 */
public interface AudioAnalyzer {

    /**
     * 分析音频文件
     *
     * @param filePath 文件绝对路径
     * @param progressCallback 进度回调，值域 0.0 ~ 1.0，可为 null
     * @return AnalysisResult 分析结果（我们自己的 DTO，不是开源库类型）
     */
    AnalysisResult analyze(String filePath, Consumer<Double> progressCallback);

    /**
     * 分析音频文件（无进度回调）
     */
    default AnalysisResult analyze(String filePath) {
        return analyze(filePath, null);
    }

    /**
     * 获取分析器名称
     */
    String getAnalyzerName();

    /**
     * 是否支持该文件格式
     */
    boolean supportsFormat(String fileExtension);
}
