package dbclient.media.model;

/**
 * 分析结果 DTO
 * 包含分析状态和分析相关字段
 */
public class AnalysisResult {
    private boolean success;
    private AnalysisStatus analysisStatus;
    private Integer bpm;
    private long durationMs;
    private String waveformCachePath;
    private boolean beatGridAvailable;
    private long analyzedAt;
    private String errorMessage;

    public AnalysisResult() {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AnalysisResult result = new AnalysisResult();

        public Builder success(boolean success) {
            result.success = success;
            return this;
        }

        public Builder analysisStatus(AnalysisStatus status) {
            result.analysisStatus = status;
            return this;
        }

        public Builder bpm(Integer bpm) {
            result.bpm = bpm;
            return this;
        }

        public Builder durationMs(long durationMs) {
            result.durationMs = durationMs;
            return this;
        }

        public Builder waveformCachePath(String waveformCachePath) {
            result.waveformCachePath = waveformCachePath;
            return this;
        }

        public Builder beatGridAvailable(boolean available) {
            result.beatGridAvailable = available;
            return this;
        }

        public Builder analyzedAt(long analyzedAt) {
            result.analyzedAt = analyzedAt;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            result.errorMessage = errorMessage;
            return this;
        }

        public AnalysisResult build() {
            return result;
        }
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public AnalysisStatus getAnalysisStatus() { return analysisStatus; }
    public void setAnalysisStatus(AnalysisStatus analysisStatus) { this.analysisStatus = analysisStatus; }

    public Integer getBpm() { return bpm; }
    public void setBpm(Integer bpm) { this.bpm = bpm; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getWaveformCachePath() { return waveformCachePath; }
    public void setWaveformCachePath(String waveformCachePath) { this.waveformCachePath = waveformCachePath; }

    public boolean isBeatGridAvailable() { return beatGridAvailable; }
    public void setBeatGridAvailable(boolean beatGridAvailable) { this.beatGridAvailable = beatGridAvailable; }

    public long getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(long analyzedAt) { this.analyzedAt = analyzedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @Override
    public String toString() {
        return "AnalysisResult{" +
                "success=" + success +
                ", analysisStatus=" + analysisStatus +
                ", bpm=" + bpm +
                ", durationMs=" + durationMs +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
