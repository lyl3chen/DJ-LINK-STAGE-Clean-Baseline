package dbclient.media.analysis;

import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.TrackInfo;

import dbclient.media.model.BeatGrid;
import dbclient.media.model.WaveformPreview;

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

            // 读取音频数据进行 BPM 分析和波形生成
            byte[] audioData = decoded.readNBytes((int) Math.min(Integer.MAX_VALUE, frames * decodedFormat.getFrameSize()));
            
            // BPM 估算
            BpmEstimate est = estimateBpm(audioData, decodedFormat);
            
            // 波形预览生成（峰值数组）
            WaveformPreview waveform = generateWaveformPreview(track.getTrackId(), audioData, decodedFormat, durationMs);
            
            // Beat Grid 生成（基于 BPM）
            BeatGrid beatGrid = null;
            if (est.bpm != null && est.bpm > 0 && durationMs > 0) {
                beatGrid = generateBeatGrid(est.bpm, durationMs);
            }

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
                .waveformPreview(waveform)
                .waveformCachePath(null) // 后续可扩展为文件缓存路径
                .beatGrid(beatGrid)
                .beatGridAvailable(beatGrid != null && beatGrid.isValid())
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
    private BpmEstimate estimateBpm(byte[] all, AudioFormat format) throws Exception {
        int channels = Math.max(1, format.getChannels());
        int frameSize = Math.max(1, format.getFrameSize());
        float sampleRate = format.getSampleRate();
        if (sampleRate <= 0) return new BpmEstimate(null, 0.0);

        // 读取最多 120 秒，避免超大文件分析过慢
        long maxFrames = (long) (sampleRate * 120);
        int bytesPerFrame = frameSize;
        int maxBytes = (int) Math.min(maxFrames * bytesPerFrame, all.length);

        if (maxBytes < frameSize * 2) return new BpmEstimate(null, 0.0);

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

    /**
     * 生成波形预览（峰值数组）
     * 
     * 生成策略：
     * - 将音频数据分成固定数量的区间（默认 1000 个峰值）
     * - 每个区间取最大绝对值作为峰值
     * - 峰值范围 -1.0 到 1.0
     * 
     * @param trackId 曲目 ID
     * @param audioData 原始音频数据（16-bit PCM）
     * @param format 音频格式
     * @param durationMs 时长（毫秒）
     * @return WaveformPreview
     */
    private WaveformPreview generateWaveformPreview(String trackId, byte[] audioData, AudioFormat format, long durationMs) {
        if (audioData == null || audioData.length < 4) {
            return null;
        }

        int channels = Math.max(1, format.getChannels());
        int frameSize = Math.max(1, format.getFrameSize());
        int totalFrames = audioData.length / frameSize;
        
        // 目标峰值数量
        final int TARGET_PEAKS = 1000;
        int samplesPerPeak = Math.max(1, totalFrames / TARGET_PEAKS);
        
        float[] peaks = new float[Math.min(TARGET_PEAKS, totalFrames)];
        
        for (int i = 0; i < peaks.length; i++) {
            int start = i * samplesPerPeak * frameSize;
            int end = Math.min(start + samplesPerPeak * frameSize, audioData.length);
            
            float maxAbs = 0f;
            for (int pos = start; pos < end; pos += frameSize) {
                if (pos + 1 >= audioData.length) break;
                int lo = audioData[pos] & 0xFF;
                int hi = audioData[pos + 1];
                short s = (short) ((hi << 8) | lo);
                float sample = s / 32768f;
                float abs = Math.abs(sample);
                if (abs > maxAbs) maxAbs = abs;
            }
            peaks[i] = maxAbs;
        }

        return WaveformPreview.builder()
            .trackId(trackId)
            .sampleRate((int) format.getSampleRate())
            .channels(channels)
            .durationMs(durationMs)
            .samplesPerPeak(samplesPerPeak)
            .peaks(peaks)
            .build();
    }

    /**
     * 生成 Beat Grid
     * 
     * MVP 策略：
     * - 基于 BPM 和时长生成线性 beat grid
     * - 假设第一个 Beat 从 0 开始
     * - 每小节 4 拍
     * 
     * @param bpm BPM
     * @param durationMs 时长（毫秒）
     * @return BeatGrid
     */
    private BeatGrid generateBeatGrid(int bpm, long durationMs) {
        if (bpm <= 0 || durationMs <= 0) {
            return null;
        }

        double beatDurationMs = 60000.0 / bpm;
        int totalBeats = (int) (durationMs / beatDurationMs) + 1;
        
        // 限制最大 beat 数量
        totalBeats = Math.min(totalBeats, 10000);

        long[] beatPositions = new long[totalBeats];
        for (int i = 0; i < totalBeats; i++) {
            beatPositions[i] = (long) (i * beatDurationMs);
        }

        return BeatGrid.builder()
            .bpm(bpm)
            .durationMs(durationMs)
            .beatsPerMeasure(4)
            .firstBeatMs(0)
            .beatPositionsMs(beatPositions)
            .build();
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
