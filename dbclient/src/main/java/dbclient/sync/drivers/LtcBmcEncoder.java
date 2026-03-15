package dbclient.sync.drivers;

/**
 * LtcBmcEncoder - LTC BMC (Biphase Mark Code) 编码器
 * 
 * BMC 规则：
 * - 每个 bit 起始必翻转电平
 * - bit=1 时，半 bit 位置再翻转一次
 */
public class LtcBmcEncoder {
    
    private final int sampleRate;
    private final int bitsPerSecond;
    
    private boolean currentLevel = false;
    private int sampleCounter = 0;
    
    public LtcBmcEncoder(int sampleRate, int bitsPerSecond) {
        this.sampleRate = sampleRate;
        this.bitsPerSecond = bitsPerSecond;
    }
    
    public int getSamplesPerBit() {
        return sampleRate / bitsPerSecond;
    }
    
    /**
     * 生成下一个 BMC 样本
     * 
     * @param bit 当前 bit 值
     * @return 音频样本值 (-1.0 ~ 1.0)
     */
    public double nextSample(boolean bit) {
        int samplesPerBit = getSamplesPerBit();
        
        // bit 边界翻转
        if (sampleCounter >= samplesPerBit) {
            currentLevel = !currentLevel;
            sampleCounter = 0;
            
            // bit=1 时，半 bit 位置再翻转
            if (bit && samplesPerBit > 1) {
                // 标记需要在半 bit 翻转
                // 简化实现：立即翻转，然后下半 bit 翻回来
                // 实际应该在 samplesPerBit/2 处翻转
                // 这里为了简化，使用状态机
            }
        }
        
        // bit=1 的半 bit 翻转处理
        if (bit && sampleCounter == samplesPerBit / 2) {
            currentLevel = !currentLevel;
        }
        
        sampleCounter++;
        return currentLevel ? 1.0 : -1.0;
    }
    
    public void reset() {
        currentLevel = false;
        sampleCounter = 0;
    }
}
