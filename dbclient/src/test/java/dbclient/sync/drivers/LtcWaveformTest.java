package dbclient.sync.drivers;

/**
 * LTC 波形验证测试
 */
public class LtcWaveformTest {
    public static void main(String[] args) {
        LtcFrameEncoder frameEncoder = new LtcFrameEncoder(25.0);
        LtcBmcEncoder bmcEncoder = new LtcBmcEncoder(48000, 2000); // 25fps * 80bits = 2000

        // 构建 frame 0
        boolean[] bits = frameEncoder.buildFrame(0);

        // 不同 gain 值测试
        double[] gains = {1.0, 0.5, 0.316}; // 0dB, -6dB, -10dB

        for (double gain : gains) {
            byte[] pcm = bmcEncoder.encodeFrame(bits, gain);

            // 统计不同电平值
            java.util.Set<Short> uniqueValues = new java.util.HashSet<>();
            for (int i = 0; i < pcm.length; i += 2) {
                short val = (short) (((pcm[i+1] & 0xFF) << 8) | (pcm[i] & 0xFF));
                uniqueValues.add(val);
            }

            System.out.printf("Gain=%.3f (%.1fdB): unique values=%d, values=%s%n",
                gain, 20*Math.log10(gain), uniqueValues.size(), uniqueValues);

            // 检查前 100 个样本
            System.out.print("First 20 samples: ");
            for (int i = 0; i < 40 && i < pcm.length; i += 2) {
                short val = (short) (((pcm[i+1] & 0xFF) << 8) | (pcm[i] & 0xFF));
                System.out.print(val + " ");
            }
            System.out.println();
            System.out.println();

            // 重置 BMC 编码器相位
            bmcEncoder.reset();
        }

        // 对比标准幅度
        System.out.println("Standard amplitude (0.50115967): " + (int)(0.50115967 * 32767));
    }
}
