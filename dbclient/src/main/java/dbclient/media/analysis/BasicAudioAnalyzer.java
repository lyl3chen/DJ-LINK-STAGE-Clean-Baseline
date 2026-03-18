package dbclient.media.analysis;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.TrackInfo;

import javax.sound.sampled.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
            AudioFormat baseFormat = stream.getFormat();

            // 统一到 PCM_SIGNED 16-bit little-endian，便于分析
            AudioFormat decodedFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                16,
                baseFormat.getChannels(),
                baseFormat.getChannels() * 2,
                baseFormat.getSampleRate(),
                false
            );
            AudioInputStream decoded = baseFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                ? stream
                : AudioSystem.getAudioInputStream(decodedFormat, stream);

            long frames = decoded.getFrameLength();
            float frameRate = decodedFormat.getFrameRate();
            long durationMs = frameRate > 0 ? (long) ((frames / frameRate) * 1000) : 0;

            BpmEstimate est = estimateBpm(decoded, decodedFormat);

            try { decoded.close(); } catch (Exception ignored) {}
            if (decoded != stream) {
                try { stream.close(); } catch (Exception ignored) {}
            }

            return AnalysisResult.builder()
                .success(true)
                .analysisStatus(AnalysisStatus.COMPLETED)
                .bpm(est.bpm)
                .bpmConfidence(est.confidence)
                .durationMs(durationMs)
                .waveformCachePath(null) // C2 实现
                .beatGridAvailable(est.bpm != null)
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
                .errorMessage("Analysis error: " + e.getClass().getSimpleName() + " - " + e.getMessage())
                .build();
        }
    }

    private static class BpmEstimate {
        final Integer bpm;
        final Double confidence;
        BpmEstimate(Integer bpm, Double confidence) {
            this.bpm = bpm;
            this.confidence = confidence;
        }
    }

    /**
     * GPL-safe minimal BPM estimator:
     * - 读取 PCM
     * - 生成能量包络 + onset
     * - 在 [60, 180] BPM 范围做自相关选峰
     */
    private BpmEstimate estimateBpm(AudioInputStream stream, AudioFormat format) throws Exception {
        int channels = Math.max(1, format.getChannels());
        int frameSize = Math.max(1, format.getFrameSize());
        float sampleRate = format.getSampleRate();
        if (sampleRate <= 0) return new BpmEstimate(null, 0.0);

        // 读取最多 120 秒，避免超大文件分析过慢
        long maxFrames = (long) (sampleRate * 120);
        int bytesPerFrame = frameSize;
        int maxBytes = (int) Math.min(Integer.MAX_VALUE, maxFrames * bytesPerFrame);

        byte[] all = stream.readNBytes(maxBytes);
        if (all.length < frameSize * 2) return new BpmEstimate(null, 0.0);

        // 16-bit little-endian PCM -> mono float
        int totalFrames = all.length / frameSize;
        float[] mono = new float[totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            int base = i * frameSize;
            float sum = 0f;
            for (int ch = 0; ch < channels; ch++) {
                int idx = base + ch * 2;
                if (idx + 1 >= all.length) continue;
                int lo = all[idx] & 0xFF;
                int hi = all[idx + 1];
                short s = (short) ((hi << 8) | lo);
                sum += (s / 32768f);
            }
            mono[i] = sum / channels;
        }

        // 包络参数
        int win = 1024;
        int hop = 512;
        int envLen = Math.max(1, (mono.length - win) / hop);
        if (envLen < 16) return new BpmEstimate(null, 0.0);

        float[] env = new float[envLen];
        for (int i = 0; i < envLen; i++) {
            int start = i * hop;
            double acc = 0;
            for (int j = 0; j < win && (start + j) < mono.length; j++) {
                float v = mono[start + j];
                acc += v * v;
            }
            env[i] = (float) Math.sqrt(acc / win);
        }

        // onset = 正向差分
        float[] onset = new float[envLen];
        onset[0] = 0;
        for (int i = 1; i < envLen; i++) {
            float d = env[i] - env[i - 1];
            onset[i] = Math.max(0f, d);
        }

        // 自相关 lag 搜索范围：60~180 BPM
        double envRate = sampleRate / hop;
        int lagMin = (int) Math.max(1, Math.floor(envRate * 60.0 / 180.0));
        int lagMax = (int) Math.max(lagMin + 1, Math.ceil(envRate * 60.0 / 60.0));

        double best = -1;
        int bestLag = -1;
        List<Double> scores = new ArrayList<>();
        for (int lag = lagMin; lag <= lagMax; lag++) {
            double s = 0;
            for (int i = lag; i < onset.length; i++) {
                s += onset[i] * onset[i - lag];
            }
            scores.add(s);
            if (s > best) {
                best = s;
                bestLag = lag;
            }
        }

        if (bestLag <= 0 || best <= 0) return new BpmEstimate(null, 0.0);

        double bpmD = 60.0 * envRate / bestLag;
        int bpm = (int) Math.round(bpmD);

        // 置信度：best / 第二峰（简化）
        double second = 0;
        for (double s : scores) {
            if (s > second && s < best) second = s;
        }
        double conf = second > 0 ? Math.min(1.0, Math.max(0.0, (best - second) / best)) : 0.5;

        // 合理范围保护
        if (bpm < 60 || bpm > 180) return new BpmEstimate(null, conf);

        return new BpmEstimate(bpm, conf);
    }

    @Override
    public String getAnalyzerName() {
        return "BasicAudioAnalyzer (Java Sound API + onset/autocorr BPM)";
    }

    @Override
    public boolean supportsFormat(String fileExtension) {
        if (fileExtension == null) return false;
        String ext = fileExtension.toLowerCase();
        // 仅支持 Java Sound API 原生格式
        return ext.equals("wav") || ext.equals("aiff") || ext.equals("au");
    }
}
