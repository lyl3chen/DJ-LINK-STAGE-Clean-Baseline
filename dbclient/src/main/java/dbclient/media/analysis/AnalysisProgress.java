package dbclient.media.analysis;

/**
 * 分析进度 DTO
 */
public class AnalysisProgress {
    private String trackId;
    private String status;  // PENDING / ANALYZING / COMPLETED / FAILED
    private int progressPercent;  // 0-100
    private String message;  // 当前阶段描述
    private long updatedAt;

    public AnalysisProgress() {
        this.updatedAt = System.currentTimeMillis();
    }

    public static AnalysisProgress pending(String trackId) {
        AnalysisProgress p = new AnalysisProgress();
        p.trackId = trackId;
        p.status = "PENDING";
        p.progressPercent = 0;
        p.message = "等待分析";
        return p;
    }

    public static AnalysisProgress analyzing(String trackId, int percent, String message) {
        AnalysisProgress p = new AnalysisProgress();
        p.trackId = trackId;
        p.status = "ANALYZING";
        p.progressPercent = Math.max(0, Math.min(100, percent));
        p.message = message;
        return p;
    }

    public static AnalysisProgress completed(String trackId) {
        AnalysisProgress p = new AnalysisProgress();
        p.trackId = trackId;
        p.status = "COMPLETED";
        p.progressPercent = 100;
        p.message = "分析完成";
        return p;
    }

    public static AnalysisProgress failed(String trackId, String errorMessage) {
        AnalysisProgress p = new AnalysisProgress();
        p.trackId = trackId;
        p.status = "FAILED";
        p.progressPercent = 0;
        p.message = errorMessage != null ? errorMessage : "分析失败";
        return p;
    }

    // Getters and Setters
    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
