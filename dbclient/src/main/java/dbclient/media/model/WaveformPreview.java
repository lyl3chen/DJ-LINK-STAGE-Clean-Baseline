package dbclient.media.model;

/**
 * Waveform Preview 数据模型
 * 
 * 设计考虑：
 * - 用于前端波形显示
 * - 预计算峰值数组，避免播放时实时计算
 * - 支持不同缩放级别（可选）
 */
public class WaveformPreview {
    private String trackId;           // 关联曲目 ID
    private int sampleRate;           // 原始采样率
    private int channels;            // 原始声道数
    private long durationMs;         // 曲目时长
    private int samplesPerPeak;      // 每个峰值代表多少样本
    private float[] peaks;           // 峰值数组（-1.0 到 1.0）
    private long generatedAt;        // 生成时间

    public WaveformPreview() {
        this.generatedAt = System.currentTimeMillis();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private WaveformPreview wp = new WaveformPreview();

        public Builder trackId(String trackId) { wp.trackId = trackId; return this; }
        public Builder sampleRate(int sampleRate) { wp.sampleRate = sampleRate; return this; }
        public Builder channels(int channels) { wp.channels = channels; return this; }
        public Builder durationMs(long durationMs) { wp.durationMs = durationMs; return this; }
        public Builder samplesPerPeak(int samplesPerPeak) { wp.samplesPerPeak = samplesPerPeak; return this; }
        public Builder peaks(float[] peaks) { wp.peaks = peaks; return this; }
        public Builder generatedAt(long generatedAt) { wp.generatedAt = generatedAt; return this; }

        public WaveformPreview build() {
            return wp;
        }
    }

    // Getters and Setters
    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

    public int getChannels() { return channels; }
    public void setChannels(int channels) { this.channels = channels; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getSamplesPerPeak() { return samplesPerPeak; }
    public void setSamplesPerPeak(int samplesPerPeak) { this.samplesPerPeak = samplesPerPeak; }

    public float[] getPeaks() { return peaks; }
    public void setPeaks(float[] peaks) { this.peaks = peaks; }

    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }

    // ==================== 便捷方法 ====================

    /**
     * 判断是否有有效的波形数据
     */
    public boolean isValid() {
        return peaks != null && peaks.length > 0 && sampleRate > 0 && durationMs > 0;
    }

    /**
     * 获取峰值数组长度
     */
    public int getPeakCount() {
        return peaks != null ? peaks.length : 0;
    }

    /**
     * 获取指定位置的峰值
     * 注意：当前 peaks 只表示幅度包络（0.0 到 1.0），不区分正负
     * 后续如需正负波形信息，可扩展
     */
    public float getPeakAt(int index) {
        if (peaks == null || index < 0 || index >= peaks.length) return 0f;
        return peaks[index];
    }

    /**
     * 获取波形总时长（毫秒）
     */
    public long getWaveformDurationMs() {
        if (!isValid()) return 0;
        return (long) ((peaks.length * samplesPerPeak) / (double) sampleRate * 1000);
    }
}
