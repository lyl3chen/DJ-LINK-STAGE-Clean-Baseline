package dbclient.sync.drivers;

/**
 * LTC BMC 编码器（连续相位）。
 *
 * 规则：
 * - 每个 bit 起始必翻转
 * - bit=1 在 half-bit 处再翻转
 * - bit=0 half-bit 不翻转
 */
public class LtcBmcEncoder {

    private static final int FRAME_BITS = 80;

    private final int samplesPerBit;
    private final int samplesPerHalfBit;

    // 连续相位状态（帧间保持）
    private boolean currentLevel = true;

    public LtcBmcEncoder(int sampleRate, int bitsPerSecond) {
        if (sampleRate <= 0 || bitsPerSecond <= 0) {
            throw new IllegalArgumentException("invalid sampleRate/bitsPerSecond");
        }
        if (sampleRate % bitsPerSecond != 0) {
            throw new IllegalArgumentException(
                "sampleRate must be divisible by bitsPerSecond for exact LTC timing: "
                    + sampleRate + "/" + bitsPerSecond);
        }

        this.samplesPerBit = sampleRate / bitsPerSecond;
        if ((this.samplesPerBit % 2) != 0) {
            throw new IllegalArgumentException("samplesPerBit must be even for exact half-bit: " + this.samplesPerBit);
        }
        this.samplesPerHalfBit = this.samplesPerBit / 2;
    }

    public byte[] encodeFrame(boolean[] frameBits, double gain) {
        if (frameBits == null || frameBits.length != FRAME_BITS) {
            throw new IllegalArgumentException("frameBits length must be 80");
        }

        if (gain < 0) gain = 0;
        int amplitude = (int) Math.round(16422.0 * gain);

        int totalSamples = FRAME_BITS * samplesPerBit;
        byte[] pcm = new byte[totalSamples * 2];
        int p = 0;

        for (int i = 0; i < FRAME_BITS; i++) {
            boolean bit = frameBits[i];

            // bit start transition
            currentLevel = !currentLevel;
            short firstHalf = (short) (currentLevel ? amplitude : -amplitude);
            p = writeConstantSamples(pcm, p, firstHalf, samplesPerHalfBit);

            // mid-bit transition only for bit=1
            if (bit) {
                currentLevel = !currentLevel;
            }
            short secondHalf = (short) (currentLevel ? amplitude : -amplitude);
            p = writeConstantSamples(pcm, p, secondHalf, samplesPerHalfBit);
        }

        return pcm;
    }

    public void reset() {
        // 保持连续相位，不强制回到固定电平
    }

    public int getSamplesPerBit() {
        return samplesPerBit;
    }

    public int getSamplesPerHalfBit() {
        return samplesPerHalfBit;
    }

    private int writeConstantSamples(byte[] out, int offset, short value, int count) {
        byte lo = (byte) (value & 0xFF);
        byte hi = (byte) ((value >> 8) & 0xFF);
        int p = offset;
        for (int i = 0; i < count; i++) {
            out[p++] = lo;
            out[p++] = hi;
        }
        return p;
    }
}
