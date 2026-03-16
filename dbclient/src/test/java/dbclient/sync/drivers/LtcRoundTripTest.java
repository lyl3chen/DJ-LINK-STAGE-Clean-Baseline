package dbclient.sync.drivers;

/**
 * LTC 编码回环自检：frame -> bits -> PCM(BMC) -> bits -> timecode
 */
public class LtcRoundTripTest {
    private static final int FPS = 25;
    private static final int SAMPLE_RATE = 48000;
    private static final int BITS_PER_SECOND = FPS * 80;
    private static final int SAMPLES_PER_BIT = SAMPLE_RATE / BITS_PER_SECOND; // 24

    public static void main(String[] args) {
        LtcFrameEncoder frameEncoder = new LtcFrameEncoder(FPS);
        LtcBmcEncoder bmcEncoder = new LtcBmcEncoder(SAMPLE_RATE, BITS_PER_SECOND);

        int ok = 0;
        int fail = 0;

        System.out.println("===== 100-frame roundtrip =====");
        for (long frame = 0; frame < 100; frame++) {
            boolean[] encodedBits = frameEncoder.buildFrame(frame);
            byte[] pcm = bmcEncoder.encodeFrame(encodedBits, 1.0);
            boolean[] decodedBits = decodeBitsFromPcm(pcm);

            Timecode encTc = frameToTc(frame);
            Timecode decTc = decodeTcFromBits(decodedBits);

            boolean bitSame = sameBits(encodedBits, decodedBits);
            boolean tcSame = encTc.equals(decTc);
            boolean pass = bitSame && tcSame;

            if (pass) ok++; else fail++;

            System.out.printf("f=%03d in=%s enc=%s dec=%s bitSame=%s tcSame=%s%n",
                    frame,
                    encTc,
                    encTc,
                    decTc,
                    bitSame,
                    tcSame);
        }

        System.out.printf("ROUNDTRIP RESULT: ok=%d fail=%d%n", ok, fail);

        // 重点边界：跨秒、跨10秒、跨10分钟
        dumpBoundary(frameEncoder, 24, 27, "cross-second");
        dumpBoundary(frameEncoder, 249, 252, "cross-10sec");
        dumpBoundary(frameEncoder, 14999, 15002, "cross-10min");

        // biphase correction / flags 位置检查
        boolean[] f0 = frameEncoder.buildFrame(0);
        System.out.printf("flags f0: b10=%d b11=%d b27=%d b43=%d b58=%d b59=%d sync=%s%n",
                bit(f0[10]), bit(f0[11]), bit(f0[27]), bit(f0[43]), bit(f0[58]), bit(f0[59]), bitsToString(f0, 64, 16));
    }

    private static void dumpBoundary(LtcFrameEncoder frameEncoder, int start, int endInclusive, String tag) {
        System.out.println("===== boundary: " + tag + " =====");
        for (int frame = start; frame <= endInclusive; frame++) {
            boolean[] bits = frameEncoder.buildFrame(frame);
            Timecode tc = frameToTc(frame);
            System.out.printf("f=%d tc=%s fU=%s fT=%s sU=%s sT=%s mU=%s mT=%s hU=%s hT=%s b27=%d b43=%d b58=%d b59=%d sync=%s%n",
                    frame,
                    tc,
                    bitsToString(bits, 0, 4),
                    bitsToString(bits, 8, 2),
                    bitsToString(bits, 16, 4),
                    bitsToString(bits, 24, 3),
                    bitsToString(bits, 32, 4),
                    bitsToString(bits, 40, 3),
                    bitsToString(bits, 48, 4),
                    bitsToString(bits, 56, 2),
                    bit(bits[27]), bit(bits[43]), bit(bits[58]), bit(bits[59]),
                    bitsToString(bits, 64, 16));
        }
    }

    private static boolean[] decodeBitsFromPcm(byte[] pcm) {
        boolean[] bits = new boolean[80];
        for (int bit = 0; bit < 80; bit++) {
            int startSample = bit * SAMPLES_PER_BIT;
            int s11 = sampleAt(pcm, startSample + (SAMPLES_PER_BIT / 2) - 1);
            int s12 = sampleAt(pcm, startSample + (SAMPLES_PER_BIT / 2));
            bits[bit] = sign(s11) != sign(s12);
        }
        return bits;
    }

    private static Timecode decodeTcFromBits(boolean[] bits) {
        int ff = decodeLsb(bits, 0, 4) + decodeLsb(bits, 8, 2) * 10;
        int ss = decodeLsb(bits, 16, 4) + decodeLsb(bits, 24, 3) * 10;
        int mm = decodeLsb(bits, 32, 4) + decodeLsb(bits, 40, 3) * 10;
        int hh = decodeLsb(bits, 48, 4) + decodeLsb(bits, 56, 2) * 10;
        return new Timecode(hh, mm, ss, ff);
    }

    private static int decodeLsb(boolean[] bits, int start, int count) {
        int v = 0;
        for (int i = 0; i < count; i++) {
            if (bits[start + i]) v |= (1 << i);
        }
        return v;
    }

    private static Timecode frameToTc(long frame) {
        int ff = (int) (frame % FPS);
        long totalSeconds = frame / FPS;
        int ss = (int) (totalSeconds % 60);
        int mm = (int) ((totalSeconds / 60) % 60);
        int hh = (int) ((totalSeconds / 3600) % 24);
        return new Timecode(hh, mm, ss, ff);
    }

    private static int sampleAt(byte[] pcm, int sampleIndex) {
        int bytePos = sampleIndex * 2;
        int lo = pcm[bytePos] & 0xFF;
        int hi = pcm[bytePos + 1];
        return (short) ((hi << 8) | lo);
    }

    private static int sign(int v) { return v >= 0 ? 1 : -1; }

    private static boolean sameBits(boolean[] a, boolean[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static int bit(boolean b) { return b ? 1 : 0; }

    private static String bitsToString(boolean[] bits, int start, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(bits[start + i] ? '1' : '0');
        return sb.toString();
    }

    private static final class Timecode {
        final int h, m, s, f;

        Timecode(int h, int m, int s, int f) {
            this.h = h; this.m = m; this.s = s; this.f = f;
        }

        @Override
        public String toString() {
            return String.format("%02d:%02d:%02d:%02d", h, m, s, f);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Timecode)) return false;
            Timecode o = (Timecode) obj;
            return h == o.h && m == o.m && s == o.s && f == o.f;
        }

        @Override
        public int hashCode() { return h * 31 * 31 * 31 + m * 31 * 31 + s * 31 + f; }
    }
}
