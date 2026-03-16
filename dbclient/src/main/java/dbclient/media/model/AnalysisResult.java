package dbclient.media.model;

/**
 * 分析结果 DTO
 * 不依赖任何开源库类型
 */
public class AnalysisResult {
    private boolean success;
    private Integer bpm;
    private double durationSec;
    private String waveformCachePath;
    private String beatGridPath;
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

        public Builder bpm(Integer bpm) {
            result.bpm = bpm;
            return this;
        }

        public Builder durationSec(double durationSec) {
            result.durationSec = durationSec;
            return this;
        }

        public Builder waveformCachePath(String waveformCachePath) {
            result.waveformCachePath = waveformCachePath;
            return this;
        }

        public Builder beatGridPath(String beatGridPath) {
            result.beatGridPath = beatGridPath;
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

    public Integer getBpm() { return bpm; }
    public void setBpm(Integer bpm) { this.bpm = bpm; }

    public double getDurationSec() { return durationSec; }
    public void setDurationSec(double durationSec) { this.durationSec = durationSec; }

    public String getWaveformCachePath() { return waveformCachePath; }
    public void setWaveformCachePath(String waveformCachePath) { this.waveformCachePath = waveformCachePath; }

    public String getBeatGridPath() { return beatGridPath; }
    public void setBeatGridPath(String beatGridPath) { this.beatGridPath = beatGridPath; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @Override
    public String toString() {
        return "AnalysisResult{" +
                "success=" + success +
                ", bpm=" + bpm +
                ", durationSec=" + durationSec +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
