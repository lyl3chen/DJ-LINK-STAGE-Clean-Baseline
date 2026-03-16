package dbclient.sync.drivers;

/**
 * LtcBmcEncoder - 标准 BMC (Biphase Mark Code) 编码器
 *
 * 严格遵循 SMPTE-12M BMC 规则和 x42/libltc 实现：
 * - 每个 bit 周期开始时必须翻转（transition）
 * - bit = 1 时，在 bit 周期中间（half-bit）再翻转一次
 * - bit = 0 时，中间不翻转
 * - 相位在帧之间保持连续
 *
 * 时序（25fps, 48kHz）：
 * - 每帧 80 bit
 * - bit rate = 80 bits × 25 fps = 2000 bits/second
 * - 每 bit 周期 = 48000 / 2000 = 24 samples
 * - half-bit = 12 samples
 */
public class LtcBmcEncoder {

    private final int sampleRate;
    private final int bitsPerSecond;
    private final int samplesPerBit;
    private final int samplesPerHalfBit;

    // BMC 状态：当前电平（在帧之间保持连续）
    private boolean currentLevel;

    public LtcBmcEncoder(int sampleRate, int bitsPerSecond) {
        this.sampleRate = sampleRate;
        this.bitsPerSecond = bitsPerSecond;
        this.samplesPerBit = sampleRate / bitsPerSecond;
        this.samplesPerHalfBit = samplesPerBit / 2;
        this.currentLevel = true; // 初始从高电平开始（参考文件第一个样本是负值，翻转后为负）
    }

    /**
     * 编码完整的 80-bit LTC 帧为 PCM 样本
     *
     * 遵循 x42/libltc 的编码规则：
     * - 每个 bit 开始前翻转
     * - bit=1 时在 half-bit 再翻转
     * - bit=0 时不翻转
     * - 保持相位连续到下一帧
     *
     * @param frameBits 80-bit LTC 帧（LSB first 顺序）
     * @param gain 增益（0.0 - 1.0）
     * @return PCM 样本数组（16-bit signed little-endian）
     */
    public byte[] encodeFrame(boolean[] frameBits, double gain) {
        int totalSamples = 80 * samplesPerBit;
        byte[] buffer = new byte[totalSamples * 2]; // 16-bit PCM
        int idx = 0;

        // 参考值：从标准 LTC WAV 文件分析
        // -16422 和 +16422 是标准幅度值
        int amplitude = (int) (16422 * gain);


        for (int bitIdx = 0; bitIdx < 80; bitIdx++) {
            boolean bit = frameBits[bitIdx];

            // 每个 bit 开始时翻转（这是 BMC 的规则）
            currentLevel = !currentLevel;

            // 前 half-bit
            int sampleValue = currentLevel ? amplitude : -amplitude;
            for (int s = 0; s < samplesPerHalfBit; s++) {
                buffer[idx++] = (byte) (sampleValue & 0xFF);
                buffer[idx++] = (byte) ((sampleValue >> 8) & 0xFF);
            }

            // 如果是 1，在 half-bit 处翻转
            if (bit) {
                currentLevel = !currentLevel;
            }

            // 后 half-bit（如果 bit=0，电平保持不变；如果 bit=1，已翻转）
            sampleValue = currentLevel ? amplitude : -amplitude;
            for (int s = 0; s < samplesPerHalfBit; s++) {
                buffer[idx++] = (byte) (sampleValue & 0xFF);
                buffer[idx++] = (byte) ((sampleValue >> 8) & 0xFF);
            }
        }

        // 注意：不重置 currentLevel，保持相位连续到下一帧

        return buffer;
    }

    /**
     * 重置编码器状态
     * 注意：不切源时不应调用，以保持帧间相位连续
     */
    public void reset() {
        // 不再重置 currentLevel，保持帧间相位连续
        // currentLevel 在切源时由调用方决定
        System.out.println("[LTC-BMC] Reset called, but preserving currentLevel=" + currentLevel);
    }

    public int getSamplesPerBit() {
        return samplesPerBit;
    }
}
