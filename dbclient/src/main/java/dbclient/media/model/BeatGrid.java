package dbclient.media.model;

/**
 * Beat Grid 数据模型
 * 
 * 设计考虑：
 * - 存储 BPM 和每拍时间点
 * - 支持时间→beat、beat→时间换算
 * - 支持不同拍子记号（beatsPerMeasure，默认 4/4）
 * - firstBeatMs 用于处理非 0 起始的歌曲
 */
public class BeatGrid {
    private int bpm;
    private long[] beatPositionsMs;  // 每拍时间点数组（毫秒）
    private long firstBeatMs;       // 第一个 Beat 的毫秒位置
    private int beatsPerMeasure;    // 每小节拍数（默认 4）
    private long durationMs;         // 曲目总时长

    public BeatGrid() {
        this.bpm = 0;
        this.beatsPerMeasure = 4;
        this.firstBeatMs = 0;
        this.durationMs = 0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BeatGrid bg = new BeatGrid();

        public Builder bpm(int bpm) { bg.bpm = bpm; return this; }
        public Builder beatPositionsMs(long[] beatPositionsMs) { bg.beatPositionsMs = beatPositionsMs; return this; }
        public Builder firstBeatMs(long firstBeatMs) { bg.firstBeatMs = firstBeatMs; return this; }
        public Builder beatsPerMeasure(int beatsPerMeasure) { bg.beatsPerMeasure = beatsPerMeasure; return this; }
        public Builder durationMs(long durationMs) { bg.durationMs = durationMs; return this; }

        public BeatGrid build() {
            return bg;
        }
    }

    // Getters and Setters
    public int getBpm() { return bpm; }
    public void setBpm(int bpm) { this.bpm = bpm; }

    public long[] getBeatPositionsMs() { return beatPositionsMs; }
    public void setBeatPositionsMs(long[] beatPositionsMs) { this.beatPositionsMs = beatPositionsMs; }

    public long getFirstBeatMs() { return firstBeatMs; }
    public void setFirstBeatMs(long firstBeatMs) { this.firstBeatMs = firstBeatMs; }

    public int getBeatsPerMeasure() { return beatsPerMeasure; }
    public void setBeatsPerMeasure(int beatsPerMeasure) { this.beatsPerMeasure = beatsPerMeasure; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    // ==================== 时间换算工具 ====================

    /**
     * 判断 Beat Grid 是否有效
     */
    public boolean isValid() {
        return bpm > 0 && beatPositionsMs != null && beatPositionsMs.length > 0;
    }

    /**
     * 毫秒时间转换为 Beat 序号（从 0 开始）
     * 
     * @param timeMs 毫秒时间
     * @return Beat 序号（整数）
     */
    public int timeToBeat(long timeMs) {
        if (!isValid() || timeMs < firstBeatMs) return 0;
        
        // 使用二分查找定位
        int beat = 0;
        for (int i = 0; i < beatPositionsMs.length; i++) {
            if (beatPositionsMs[i] <= timeMs) {
                beat = i;
            } else {
                break;
            }
        }
        return beat;
    }

    /**
     * Beat 序号转换为毫秒时间
     * 
     * @param beat Beat 序号（从 0 开始）
     * @return 毫秒时间
     */
    public long beatToTime(int beat) {
        if (!isValid() || beat < 0) return 0;
        
        // 如果在已知范围内
        if (beatPositionsMs != null && beat < beatPositionsMs.length) {
            return beatPositionsMs[beat];
        }
        
        // 超出范围，用线性计算
        double beatDurationMs = 60000.0 / bpm;
        return firstBeatMs + (long) (beat * beatDurationMs);
    }

    /**
     * 获取当前 Beat 内的相位（0.0 - 1.0）
     * 
     * @param timeMs 当前时间（毫秒）
     * @return 相位值
     */
    public double getPhaseAtTime(long timeMs) {
        if (!isValid()) return 0.0;
        
        int currentBeat = timeToBeat(timeMs);
        long currentBeatTime = beatToTime(currentBeat);
        long nextBeatTime = beatToTime(currentBeat + 1);
        
        if (nextBeatTime <= currentBeatTime) return 0.0;
        
        double phase = (double) (timeMs - currentBeatTime) / (nextBeatTime - currentBeatTime);
        return Math.max(0.0, Math.min(1.0, phase));
    }

    /**
     * 获取下一个 Beat 的时间
     * 
     * @param timeMs 当前时间（毫秒）
     * @return 下一个 Beat 的时间
     */
    public long getNextBeatTime(long timeMs) {
        if (!isValid()) return timeMs;
        
        int nextBeat = timeToBeat(timeMs) + 1;
        return beatToTime(nextBeat);
    }

    /**
     * 获取当前小节序号（从 0 开始）
     * 
     * @param timeMs 当前时间（毫秒）
     * @return 小节序号
     */
    public int getMeasureAtTime(long timeMs) {
        if (!isValid()) return 0;
        return timeToBeat(timeMs) / beatsPerMeasure;
    }

    /**
     * 获取下一个小节的时间
     * 
     * @param timeMs 当前时间（毫秒）
     * @return 下一个小节的时间
     */
    public long getNextMeasureTime(long timeMs) {
        if (!isValid()) return timeMs;
        
        int currentMeasure = getMeasureAtTime(timeMs);
        int nextMeasure = currentMeasure + 1;
        return beatToTime(nextMeasure * beatsPerMeasure);
    }

    @Override
    public String toString() {
        return "BeatGrid{" +
                "bpm=" + bpm +
                ", firstBeatMs=" + firstBeatMs +
                ", beatsPerMeasure=" + beatsPerMeasure +
                ", beatCount=" + (beatPositionsMs != null ? beatPositionsMs.length : 0) +
                ", durationMs=" + durationMs +
                '}';
    }
}
