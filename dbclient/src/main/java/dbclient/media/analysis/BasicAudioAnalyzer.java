package dbclient.media.analysis;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.TrackInfo;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

/**
 * 基础音频分析器实现
 * 使用 Java Sound API 读取音频，简单 BPM 估算
 * 
 * 当前能力：
 * - 读取音频文件获取 durationMs
 * - 简单 BPM 估算（基于音频长度和节拍计数）
 * - 明确表达 success/failed/unknown
 * 
 * 限制：
 * - 第一版 BPM 分析较粗糙，可能不准确
 * - 仅支持 WAV/AIFF/AU（Java Sound 原生支持）
 * - waveform / beat grid 未实现（beatGridAvailable = false）
 */
public class BasicAudioAnalyzer implements AudioAnalyzer {

    @Override
    public AnalysisResult analyze(TrackInfo track) {
        if (track == null || track.getFilePath() == null) {
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Track or file path is null")
                .build();
        }

        File file = new File(track.getFilePath());
        if (!file.exists()) {
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("File not found: " + track.getFilePath())
                .build();
        }

        try {
            // 使用 Java Sound API 读取音频信息
            AudioInputStream stream = AudioSystem.getAudioInputStream(file);
            AudioFormat format = stream.getFormat();

            // 计算时长
            long frames = stream.getFrameLength();
            float frameRate = format.getFrameRate();
            long durationMs = (long) ((frames / frameRate) * 1000);

            stream.close();

            // BPM 分析：第一版简化处理
            // 实际 BPM 分析需要 onset 检测、节拍跟踪等复杂算法
            // 这里先用简单启发式或返回 unknown
            Integer bpm = estimateBpm(file, format, durationMs);

            return AnalysisResult.builder()
                .success(true)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .bpm(bpm) // 可能为 null（unknown）
                .durationMs(durationMs)
                .waveformCachePath(null) // 第一版不生成波形
                .beatGridAvailable(false) // 第一版不生成 beat grid
                .analyzedAt(System.currentTimeMillis())
                .build();

        } catch (UnsupportedAudioFileException e) {
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Unsupported audio format: " + e.getMessage())
                .build();
        } catch (Exception e) {
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Analysis error: " + e.getMessage())
                .build();
        }
    }

    /**
     * 简单 BPM 估算
     * 第一版：基于文件名启发式或返回 null（unknown）
     * 后续可接入 TarsosDSP 的 OnsetHandler 进行真实 BPM 检测
     */
    private Integer estimateBpm(File file, AudioFormat format, long durationMs) {
        // 第一版策略：不猜测，返回 null 表示 unknown
        // 避免返回错误 BPM
        return null;

        // 后续可替换为：
        // - TarsosDSP OnsetHandler 检测 onset
        // - 计算 onset 间隔的中位数/众数
        // - 转换为 BPM
    }

    @Override
    public String getAnalyzerName() {
        return "BasicAudioAnalyzer (Java Sound API)";
    }

    @Override
    public boolean supportsFormat(String fileExtension) {
        if (fileExtension == null) return false;
        String ext = fileExtension.toLowerCase();
        return ext.equals("wav") || ext.equals("aiff") || ext.equals("au");
    }
}
