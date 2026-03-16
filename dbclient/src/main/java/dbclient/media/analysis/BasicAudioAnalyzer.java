package dbclient.media.analysis;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.TrackInfo;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

/**
 * 基础音频分析器实现
 * 
 * 当前实现：
 * - 使用 Java Sound API（javax.sound.sampled）读取音频文件
 * - 可获取基础音频信息：durationMs、sampleRate、channels
 * - BPM 分析：未实现，返回 null（unknown）
 * 
 * 限制：
 * - 仅支持 WAV/AIFF/AU（Java Sound API 原生支持）
 * - MP3 需要额外解码器（如 JLayer），当前不支持
 * - BPM 分析未实现，返回 null
 * - waveform / beat grid 未实现
 * 
 * 后续扩展：
 * - 可接入 TarsosDSP 实现真实 BPM 检测
 * - 可接入 JLayer 支持 MP3 格式
 * - 可生成波形预览和节拍网格
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

            // BPM 分析：当前版本未实现，返回 null（unknown）
            // 后续可接入 TarsosDSP OnsetHandler 实现真实 BPM 检测
            Integer bpm = null; // unknown

            return AnalysisResult.builder()
                .success(true)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .bpm(bpm) // null 表示 unknown
                .durationMs(durationMs)
                .waveformCachePath(null) // 未实现
                .beatGridAvailable(false) // 未实现
                .analyzedAt(System.currentTimeMillis())
                .build();

        } catch (UnsupportedAudioFileException e) {
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Unsupported audio format. Only WAV/AIFF/AU supported. " + e.getMessage())
                .build();
        } catch (Exception e) {
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Analysis error: " + e.getMessage())
                .build();
        }
    }

    @Override
    public String getAnalyzerName() {
        return "BasicAudioAnalyzer (Java Sound API only, BPM not implemented)";
    }

    @Override
    public boolean supportsFormat(String fileExtension) {
        if (fileExtension == null) return false;
        String ext = fileExtension.toLowerCase();
        // 仅支持 Java Sound API 原生格式
        return ext.equals("wav") || ext.equals("aiff") || ext.equals("au");
    }
}
