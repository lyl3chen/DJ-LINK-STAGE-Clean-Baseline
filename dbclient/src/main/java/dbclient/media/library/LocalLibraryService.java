package dbclient.media.library;

import dbclient.media.analysis.AudioAnalyzer;
import dbclient.media.analysis.BasicAudioAnalyzer;
import dbclient.media.model.AnalysisResult;
import dbclient.media.model.AnalysisStatus;
import dbclient.media.model.TrackInfo;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 本地曲库服务（最小实现）
 * 
 * 职责：
 * - 导入本地文件到曲库
 * - 触发音频分析
 * - 保存分析结果
 * 
 * 限制（第一版）：
 * - 不做复杂目录扫描
 * - 不做批量任务系统
 * - 不做多 deck
 * - 分析为同步或简单异步
 */
/**
 * LocalLibraryService = 本地曲库用例编排层。
 *
 * 负责：导入/查询/删除/分析触发。
 * 不负责：播放控制、时间码输出。
 */
public class LocalLibraryService {

    private final TrackRepository trackRepository;
    private final AudioAnalyzer audioAnalyzer;

    public LocalLibraryService() {
        // 默认使用内存仓储和基础分析器
        this(new InMemoryTrackRepository(), new BasicAudioAnalyzer());
    }

    public LocalLibraryService(TrackRepository repository, AudioAnalyzer analyzer) {
        this.trackRepository = repository;
        this.audioAnalyzer = analyzer;
    }

    /**
     * 导入单个文件到曲库
     * 
     * @param filePath 文件绝对路径
     * @return 导入的曲目信息（包含生成的 trackId）
     */
    public TrackInfo importFile(String filePath) {
        // 检查文件是否已存在
        Optional<TrackInfo> existing = trackRepository.findByFilePath(filePath);
        if (existing.isPresent()) {
            System.out.println("[LocalLibraryService] File already imported: " + filePath);
            return existing.get();
        }

        // 读取音频文件基本信息
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("[LocalLibraryService] File not found: " + filePath);
            return null;
        }

        try {
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
            
            // 提取基本信息
            String fileName = file.getName();
            String title = extractTitle(fileName);
            String artist = "Unknown"; // 第一版不从元数据读取
            long durationMs = 0;
            int sampleRate = (int) fileFormat.getFormat().getSampleRate();
            int channels = fileFormat.getFormat().getChannels();

            // 创建 TrackInfo
            TrackInfo track = TrackInfo.builder()
                .trackId(UUID.randomUUID().toString())
                .filePath(filePath)
                .title(title)
                .artist(artist)
                .durationMs(durationMs) // 稍后分析时补全
                .sampleRate(sampleRate)
                .channels(channels)
                .build();

            // 保存到曲库
            trackRepository.save(track);
            System.out.println("[LocalLibraryService] Imported: " + title + " (" + track.getTrackId() + ")");

            // 立即触发分析（第一版简化：同步分析）
            analyzeTrack(track.getTrackId());

            return track;

        } catch (Exception e) {
            System.err.println("[LocalLibraryService] Import error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 分析指定曲目
     * 
     * @param trackId 曲目 ID
     * @return 分析结果
     */
    public AnalysisResult analyzeTrack(String trackId) {
        Optional<TrackInfo> trackOpt = trackRepository.findById(trackId);
        if (trackOpt.isEmpty()) {
            System.err.println("[LocalLibraryService] Track not found: " + trackId);
            return AnalysisResult.builder()
                .success(false)
                .analysisStatus(AnalysisStatus.FAILED)
                .errorMessage("Track not found")
                .build();
        }

        TrackInfo track = trackOpt.get();
        System.out.println("[LocalLibraryService] Analyzing: " + track.getTitle());

        // 执行分析
        AnalysisResult result = audioAnalyzer.analyze(track);

        // 如果分析成功，更新曲目 duration（如果从文件读取失败）
        if (result.isSuccess() && result.getDurationMs() > 0) {
            track.setDurationMs(result.getDurationMs());
            trackRepository.save(track);
        }

        // 保存分析结果
        trackRepository.saveAnalysis(trackId, result);

        System.out.println("[LocalLibraryService] Analysis completed: " + track.getTitle() + 
            " (BPM: " + (result.getBpm() != null ? result.getBpm() : "unknown") + ")");

        return result;
    }

    /**
     * 异步分析（简单封装）
     */
    public CompletableFuture<AnalysisResult> analyzeTrackAsync(String trackId) {
        return CompletableFuture.supplyAsync(() -> analyzeTrack(trackId));
    }

    /**
     * 获取曲目信息
     */
    public Optional<TrackInfo> getTrack(String trackId) {
        return trackRepository.findById(trackId);
    }

    /**
     * 获取曲目分析结果
     */
    public Optional<AnalysisResult> getAnalysis(String trackId) {
        return trackRepository.getAnalysis(trackId);
    }

    /**
     * 获取所有曲目
     */
    public java.util.List<TrackInfo> getAllTracks() {
        return trackRepository.findAll();
    }

    /**
     * 删除曲目
     *
     * @param trackId 曲目 ID
     * @return 是否删除成功
     */
    public boolean deleteTrack(String trackId) {
        if (trackId == null || trackId.isEmpty()) return false;
        if (!trackRepository.exists(trackId)) return false;
        trackRepository.delete(trackId);
        return true;
    }

    /**
     * 获取曲库仓储（用于高级操作）
     */
    public TrackRepository getRepository() {
        return trackRepository;
    }

    /**
     * 从文件名提取标题（简化）
     */
    private String extractTitle(String fileName) {
        // 移除扩展名
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }
}
