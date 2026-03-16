package dbclient.sync.drivers;

/**
 * LTC 80-bit 帧编码器（按 x42/libltc 常用 25fps non-drop 线序）
 *
 * 线序规则：
 * - frame[] 下标即线路发送顺序（bit0 -> bit79）
 * - BCD 字段按 LSB-first 写入到各自 bit 槽位
 * - Sync Word 固定 0x3FFD 的线路位序：0011111111111101
 */
public class LtcFrameEncoder {

    private static final int FRAME_BITS = 80;
    private static final String SYNC_WORD_BITS = "0011111111111101"; // bits[64..79]

    private final int fps;

    public LtcFrameEncoder(double frameRate) {
        this.fps = (int) Math.round(frameRate);
        if (this.fps <= 0) throw new IllegalArgumentException("invalid frame rate: " + frameRate);
    }

    /**
     * 由总帧号构建 LTC 帧（00:00:00:00 起）。
     */
    public boolean[] buildFrame(long absoluteFrame) {
        if (absoluteFrame < 0) absoluteFrame = 0;

        int ff = (int) (absoluteFrame % fps);
        long totalSeconds = absoluteFrame / fps;
        int ss = (int) (totalSeconds % 60);
        int mm = (int) ((totalSeconds / 60) % 60);
        int hh = (int) ((totalSeconds / 3600) % 24);

        return buildFrame(hh, mm, ss, ff, false, false, false, false);
    }

    /**
     * 由冻结快照时间码构建 LTC 帧。
     */
    public boolean[] buildFrame(int hh, int mm, int ss, int ff,
                                boolean dropFrame,
                                boolean colorFrame,
                                boolean bgf0,
                                boolean bgf1) {
        validate(hh, 0, 23, "hh");
        validate(mm, 0, 59, "mm");
        validate(ss, 0, 59, "ss");
        validate(ff, 0, fps - 1, "ff");

        boolean[] bits = new boolean[FRAME_BITS];

        // time fields
        putBcdLsb(bits, 0, ff % 10, 4);      // frame units
        putBcdLsb(bits, 8, ff / 10, 2);      // frame tens

        bits[10] = dropFrame;                // DF
        bits[11] = colorFrame;               // CF

        putBcdLsb(bits, 16, ss % 10, 4);     // sec units
        putBcdLsb(bits, 24, ss / 10, 3);     // sec tens

        bits[27] = bgf0;                     // BGF0

        putBcdLsb(bits, 32, mm % 10, 4);     // min units
        putBcdLsb(bits, 40, mm / 10, 3);     // min tens

        bits[43] = bgf1;                     // BGF1

        putBcdLsb(bits, 48, hh % 10, 4);     // hour units
        putBcdLsb(bits, 56, hh / 10, 2);     // hour tens

        bits[58] = false;                    // reserved / BGF2 in some variants
        bits[59] = false;                    // BGF2 / reserved (25fps non-drop 下默认 0)

        // user bits (4-7,12-15,20-23,28-31,36-39,44-47,52-55,60-63) 默认为 0

        // sync word bits[64..79]
        for (int i = 0; i < 16; i++) {
            bits[64 + i] = SYNC_WORD_BITS.charAt(i) == '1';
        }

        return bits;
    }

    private void putBcdLsb(boolean[] bits, int start, int value, int width) {
        for (int i = 0; i < width; i++) {
            bits[start + i] = ((value >> i) & 1) != 0;
        }
    }

    private void validate(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " out of range: " + value);
        }
    }
}
