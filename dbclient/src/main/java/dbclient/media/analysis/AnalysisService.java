package dbclient.media.analysis;

import dbclient.media.library.TrackLibraryRepository;
import dbclient.media.library.JsonFileTrackLibraryRepository;
import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.BeatGrid;
import dbclient.media.model.TrackLibraryEntry;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析服务
 * 
 * 职责边界：
 * - 负责分析任务调度、状态流转、结果保存
 * - 不处理曲目资产的 CRUD（由 TrackLibraryService 处理）
 * - 不处理播放逻辑（由 BasicLocalPlaybackEngine 处理）
 * 
 * 分析状态：
 * - PENDING: 待分析
 * - ANALYZING: 分析中
 * - COMPLETED: 分析成功
 * - FAILED: 分析失败
 */
public class AnalysisService {

    private final AudioAnalyzer audioAnalyzer;
    private final TrackLibraryRepository trackRepo;
    private final Map<String, AnalysisProgress> progressMap = new ConcurrentHashMap<>();

    public AnalysisService() {
        this(new BasicAudioAnalyzer(), new JsonFileTrackLibraryRepository());
    }

    public AnalysisService(AudioAnalyzer audioAnalyzer, TrackLibraryRepository trackRepo) {
        this.audioAnalyzer = audioAnalyzer;
        this.trackRepo = trackRepo;
    }

    /**
     * 同步分析曲目
     * 
     * @param trackId 曲目 ID
     * @return 分析结果
     */
    public AnalysisResult analyzeTrack(String trackId) {
        Optional<TrackLibraryEntry> entryOpt = trackRepo.findByTrackId(trackId);
        if (entryOpt.isEmpty()) {
            progressMap.put(trackId, AnalysisProgress.failed(trackId, "Track not found: " + trackId));
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Track not found: " + trackId)
                .build();
        }

        TrackLibraryEntry entry = entryOpt.get();

        // 1. 设置分析状态为 ANALYZING (0%)
        progressMap.put(trackId, AnalysisProgress.analyzing(trackId, 0, "开始分析"));
        AnalysisResult analyzingResult = AnalysisResult.builder()
            .success(false)
            .analysisStatus(AnalysisStatus.ANALYZING)
            .build();
        entry.setAnalysis(analyzingResult);
        trackRepo.save(entry);

        // 2. 执行分析（带进度更新）
        AnalysisResult result;
        try {
            // 构建临时 TrackInfo 传给分析器
            dbclient.media.model.TrackInfo trackInfo = dbclient.media.model.TrackInfo.builder()
                .trackId(entry.getTrackId())
                .filePath(entry.getFilePath())
                .title(entry.getTitle())
                .durationMs(entry.getDurationMs())
                .sampleRate(entry.getSampleRate())
                .channels(entry.getChannels())
                .build();

            // 读取音频文件阶段 (0-30%)
            progressMap.put(trackId, AnalysisProgress.analyzing(trackId, 10, "读取音频文件"));
            
            result = audioAnalyzer.analyze(trackInfo);
            
            // BPM 检测完成 (30-60%)
            if (result.isSuccess() && result.getBpm() != null) {
                progressMap.put(trackId, AnalysisProgress.analyzing(trackId, 50, "检测到 BPM: " + result.getBpm()));
            }
        } catch (Exception e) {
            progressMap.put(trackId, AnalysisProgress.failed(trackId, e.getMessage()));
            result = AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Analysis error: " + e.getMessage())
                .build();
        }

        // 3. 更新资产记录
        entry.setAnalysis(result);
        
        // 4. 如果分析成功，生成 Beat Grid (60-100%)
        if (result.isSuccess() && result.getBpm() != null && result.getDurationMs() > 0) {
            progressMap.put(trackId, AnalysisProgress.analyzing(trackId, 70, "生成 Waveform"));
            
            BeatGrid beatGrid = generateBeatGrid(result.getBpm(), result.getDurationMs());
            result.setBeatGrid(beatGrid);
            result.setBeatGridAvailable(true);
            
            progressMap.put(trackId, AnalysisProgress.analyzing(trackId, 90, "生成 Beat Grid"));
        }
        
        trackRepo.save(entry);

        // 5. 更新进度为完成或失败
        if (result.isSuccess()) {
            progressMap.put(trackId, AnalysisProgress.completed(trackId));
        } else {
            progressMap.put(trackId, AnalysisProgress.failed(trackId, result.getErrorMessage()));
        }

        return result;
    }

    /**
     * 异步分析曲目
     */
    public CompletableFuture<AnalysisResult> analyzeTrackAsync(String trackId) {
        return CompletableFuture.supplyAsync(() -> analyzeTrack(trackId));
    }

    /**
     * 生成基础 Beat Grid
     * 
     * MVP 策略：
     * - 基于 BPM 和时长生成线性 beat grid
     * - 假设第一个 Beat 从 0 开始
     * - 每小节 4 拍
     * 
     * BeatGrid 无效值语义：
     * - beatGrid == null: 无有效 beat grid
     * - !beatGrid.isValid(): BPM 无效或分析失败
     * 
     * @param bpm BPM
     * @param durationMs 时长（毫秒）
     * @return BeatGrid
     */
    private BeatGrid generateBeatGrid(int bpm, long durationMs) {
        if (bpm <= 0 || durationMs <= 0) {
            return BeatGrid.builder()
                .bpm(bpm)
                .durationMs(durationMs)
                .beatsPerMeasure(4)
                .firstBeatMs(0)
                .beatPositionsMs(new long[0])
                .build();
        }

        double beatDurationMs = 60000.0 / bpm;
        int totalBeats = (int) (durationMs / beatDurationMs) + 1;
        
        // 限制最大 beat 数量（避免超大数组）
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

    /**
     * 检查 Beat Grid 是否有效（Service 层兜底）
     * 
     * @param beatGrid BeatGrid
     * @return 是否有效
     */
    public boolean isBeatGridValid(BeatGrid beatGrid) {
        return beatGrid != null && beatGrid.isValid();
    }

    /**
     * 获取安全的 Beat Grid（无效时返回保守值）
     * 
     * @param beatGrid 原始 BeatGrid（可能为 null）
     * @return 有效 BeatGrid 或保守空对象
     */
    public BeatGrid getSafeBeatGrid(BeatGrid beatGrid) {
        if (beatGrid == null || !beatGrid.isValid()) {
            // 返回保守空对象
            return BeatGrid.builder()
                .bpm(0)
                .durationMs(0)
                .beatsPerMeasure(4)
                .firstBeatMs(0)
                .beatPositionsMs(new long[0])
                .build();
        }
        return beatGrid;
    }

    /**
     * 查询分析结果
     */
    public Optional<AnalysisResult> getAnalysis(String trackId) {
        return trackRepo.findByTrackId(trackId)
            .map(TrackLibraryEntry::getAnalysis);
    }

    /**
     * 获取分析进度
     * 
     * @param trackId 曲目 ID
     * @return 分析进度，如果没有则返回 PENDING 状态
     */
    public AnalysisProgress getProgress(String trackId) {
        return progressMap.getOrDefault(trackId, AnalysisProgress.pending(trackId));
    }
    
    /**
     * 清除进度记录
     * 
     * @param trackId 曲目 ID
     */
    public void clearProgress(String trackId) {
        progressMap.remove(trackId);
    }

    /**
     * 获取分析器名称
     */
    public String getAnalyzerName() {
        return audioAnalyzer.getAnalyzerName();
    }

    /**
     * 检查是否支持指定格式
     */
    public boolean supportsFormat(String fileExtension) {
        return audioAnalyzer.supportsFormat(fileExtension);
    }
}
