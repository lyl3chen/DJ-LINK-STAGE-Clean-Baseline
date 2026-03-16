package dbclient.sync.drivers;

/**
 * LTC 编码诊断工具：打印连续 20 帧的构帧内容与 BMC 边界。
 */
public class LtcEncodeTest {
    private static final int SAMPLE_RATE = 48000;
    private static final int FPS = 25;
    private static final int BITS_PER_FRAME = 80;
    private static final int BITS_PER_SECOND = FPS * BITS_PER_FRAME;
    private static final int SAMPLES_PER_BIT = SAMPLE_RATE / BITS_PER_SECOND; // 24 @25fps

    public static void main(String[] args) {
        long startFrame = args.length > 0 ? Long.parseLong(args[0]) : 0;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 20;

        LtcFrameEncoder frameEncoder = new LtcFrameEncoder(FPS);
        LtcBmcEncoder bmcEncoder = new LtcBmcEncoder(SAMPLE_RATE, BITS_PER_SECOND);

        System.out.printf("===== LTC DEBUG DUMP (%d frames from %d) =====%n", count, startFrame);
        for (long frame = startFrame; frame < startFrame + count; frame++) {
            int ff = (int) (frame % FPS);
            long totalSeconds = frame / FPS;
            int ss = (int) (totalSeconds % 60);
            int mm = (int) ((totalSeconds / 60) % 60);
            int hh = (int) ((totalSeconds / 3600) % 24);

            boolean[] bits = frameEncoder.buildFrame(frame);

            String frameBits = bitsToString(bits, 0, 80);
            String fUnits = bitsToString(bits, 0, 4);
            String fTens = bitsToString(bits, 8, 2);
            String sUnits = bitsToString(bits, 16, 4);
            String sTens = bitsToString(bits, 24, 3);
            String mUnits = bitsToString(bits, 32, 4);
            String mTens = bitsToString(bits, 40, 3);
            String hUnits = bitsToString(bits, 48, 4);
            String hTens = bitsToString(bits, 56, 2);
            String sync = bitsToString(bits, 64, 16);

            System.out.printf("[F%02d] currentFrame=%d tc=%02d:%02d:%02d:%02d%n", frame, frame, hh, mm, ss, ff);
            System.out.printf("      frame80=%s%n", frameBits);
            System.out.printf("      fields: fU=%s fT=%s sU=%s sT=%s mU=%s mT=%s hU=%s hT=%s sync=%s%n",
                    fUnits, fTens, sUnits, sTens, mUnits, mTens, hUnits, hTens, sync);

            byte[] pcm = bmcEncoder.encodeFrame(bits, 1.0);
            dumpBmcBoundary(frame, bits, pcm);
        }
    }

    private static void dumpBmcBoundary(long frame, boolean[] bits, byte[] pcm) {
        // 只检查前 3 bit + 最后 2 bit 的边界行为，避免日志过大
        int[] checkBits = {0, 1, 2, 78, 79};
        StringBuilder sb = new StringBuilder();
        sb.append("      bmc: ");
        for (int bitIndex : checkBits) {
            int bitStartSample = bitIndex * SAMPLES_PER_BIT;
            int sampleStart = sampleAt(pcm, bitStartSample);
            int sampleHalfMinus1 = sampleAt(pcm, bitStartSample + (SAMPLES_PER_BIT / 2) - 1);
            int sampleHalf = sampleAt(pcm, bitStartSample + (SAMPLES_PER_BIT / 2));
            int sampleEnd = sampleAt(pcm, bitStartSample + SAMPLES_PER_BIT - 1);

            boolean startTransition = (bitIndex == 0)
                    ? true
                    : sign(sampleAt(pcm, bitStartSample - 1)) != sign(sampleStart);
            boolean midTransition = sign(sampleHalfMinus1) != sign(sampleHalf);

            sb.append(String.format("b%d[%d]:S=%s M=%s (%d,%d,%d,%d) ",
                    bitIndex,
                    bits[bitIndex] ? 1 : 0,
                    startTransition ? "T" : "-",
                    midTransition ? "T" : "-",
                    sampleStart, sampleHalfMinus1, sampleHalf, sampleEnd));
        }

        // 帧尾到下一帧头的连续性（这里只能在单帧编码内观察尾部）
        int lastSample = sampleAt(pcm, BITS_PER_FRAME * SAMPLES_PER_BIT - 1);
        sb.append(String.format("| frameEndSample=%d", lastSample));

        System.out.println(sb);
    }

    private static int sign(int v) {
        return v >= 0 ? 1 : -1;
    }

    private static int sampleAt(byte[] pcm, int sampleIndex) {
        int bytePos = sampleIndex * 2;
        int lo = pcm[bytePos] & 0xFF;
        int hi = pcm[bytePos + 1];
        return (short) ((hi << 8) | lo);
    }

    private static String bitsToString(boolean[] bits, int start, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(bits[start + i] ? '1' : '0');
        }
        return sb.toString();
    }
}
