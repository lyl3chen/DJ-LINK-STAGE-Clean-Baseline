package dbclient.sync.drivers;

/**
 * LtcFrameEncoder - 标准 SMPTE LTC 帧编码器
 * 
 * 严格遵循 SMPTE-12M 标准：
 * - 80 bit 固定帧结构
 * - BCD 编码时间码（按 LTC 线序：LSB first）
 * - 标准 sync word (0x3FFD，按 LTC 线序写入)
 * - 所有时间字段按 LTC bit 线序输出
 */
public class LtcFrameEncoder {
    
    private final double frameRate;
    
    public LtcFrameEncoder(double frameRate) {
        this.frameRate = frameRate;
    }
    
    /**
     * 将时间码编码为 80-bit LTC 帧
     * 
     * SMPTE LTC 帧结构（bits 0-79，LSB first 传输）：
     * - Bits 0-3: Frame Units BCD
     * - Bits 4-7: User Bits 0-3
     * - Bits 8-9: Frame Tens BCD
     * - Bit 10: Drop Frame Flag
     * - Bit 11: Color Frame Flag
     * - Bits 12-15: User Bits 4-7
     * - Bits 16-19: Seconds Units BCD
     * - Bits 20-23: User Bits 8-11
     * - Bits 24-26: Seconds Tens BCD
     * - Bit 27: Binary Group Flag 0
     * - Bits 28-31: User Bits 12-15
     * - Bits 32-35: Minutes Units BCD
     * - Bits 36-39: User Bits 16-19
     * - Bits 40-42: Minutes Tens BCD
     * - Bit 43: Binary Group Flag 1
     * - Bits 44-47: User Bits 20-23
     * - Bits 48-51: Hours Units BCD
     * - Bits 52-55: User Bits 24-27
     * - Bits 56-57: Hours Tens BCD
     * - Bit 58: Reserved
     * - Bit 59: Binary Group Flag 2
     * - Bits 60-63: User Bits 28-31
     * - Bits 64-79: Sync Word (0x3FFD)
     * 
     * @param frame 总帧号
     * @return 80-bit 布尔数组，索引 0-79 对应 bits 0-79（LSB first）
     */
    public boolean[] buildFrame(long frame) {
        if (frame < 0) frame = 0;
        
        // 转换为 hh:mm:ss:ff
        long totalFrames = frame;
        int ff = (int) (totalFrames % (int) frameRate);
        long totalSeconds = totalFrames / (int) frameRate;
        int ss = (int) (totalSeconds % 60);
        int mm = (int) ((totalSeconds / 60) % 60);
        int hh = (int) ((totalSeconds / 3600) % 24);
        
        // BCD 拆分
        int frameUnits = ff % 10;
        int frameTens = ff / 10;
        int secUnits = ss % 10;
        int secTens = ss / 10;
        int minUnits = mm % 10;
        int minTens = mm / 10;
        int hourUnits = hh % 10;
        int hourTens = hh / 10;
        
        // 调试日志 - 每帧都打印
        System.out.printf("[LTC-FRAME] frame=%d -> %02d:%02d:%02d:%02d (f=%d.%d s=%d.%d m=%d.%d h=%d.%d)%n", 
            frame, hh, mm, ss, ff, 
            frameUnits, frameTens, secUnits, secTens, minUnits, minTens, hourUnits, hourTens);
        
        boolean[] bits = new boolean[80];
        int idx = 0;
        
        // Bits 0-3: Frame Units (BCD, LSB first)
        writeBcdLsbFirst(bits, idx, frameUnits, 4); idx += 4;
        // Bits 4-7: User Bits 0-3
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 8-9: Frame Tens (BCD, LSB first)
        writeBcdLsbFirst(bits, idx, frameTens, 2); idx += 2;
        // Bit 10: Drop Frame Flag (0 for 25fps)
        bits[idx++] = false;
        // Bit 11: Color Frame Flag (0)
        bits[idx++] = false;
        // Bits 12-15: User Bits 4-7
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 16-19: Seconds Units (BCD, LSB first)
        writeBcdLsbFirst(bits, idx, secUnits, 4); idx += 4;
        // Bits 20-23: User Bits 8-11
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 24-26: Seconds Tens (BCD, 3 bits, LSB first)
        writeBcdLsbFirst(bits, idx, secTens, 3); idx += 3;
        // Bit 27: Binary Group Flag 0 (0)
        bits[idx++] = false;
        // Bits 28-31: User Bits 12-15
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 32-35: Minutes Units (BCD, LSB first)
        writeBcdLsbFirst(bits, idx, minUnits, 4); idx += 4;
        // Bits 36-39: User Bits 16-19
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 40-42: Minutes Tens (BCD, 3 bits, LSB first)
        writeBcdLsbFirst(bits, idx, minTens, 3); idx += 3;
        // Bit 43: Binary Group Flag 1 (0)
        bits[idx++] = false;
        // Bits 44-47: User Bits 20-23
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 48-51: Hours Units (BCD, LSB first)
        writeBcdLsbFirst(bits, idx, hourUnits, 4); idx += 4;
        // Bits 52-55: User Bits 24-27
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 56-57: Hours Tens (BCD, 2 bits, LSB first)
        writeBcdLsbFirst(bits, idx, hourTens, 2); idx += 2;
        // Bit 58: Reserved / Clock Flag (0)
        bits[idx++] = false;
        // Bit 59: Binary Group Flag 2 (0)
        bits[idx++] = false;
        // Bits 60-63: User Bits 28-31
        writeZeros(bits, idx, 4); idx += 4;
        
        // Bits 64-79: Sync Word
        // 参考文件显示 sync word 按 MSB first 传输
        // 0x3FFD = 00111111 11111101 (MSB first)
        // bits[64] = 0 (MSB), bits[79] = 1 (LSB)
        boolean[] syncWord = {
            false, false, true, true, true, true, true, true,   // 0x3F (MSB first)
            true, true, true, true, true, true, false, true      // 0xFD (MSB first)
        };
        
        System.arraycopy(syncWord, 0, bits, 64, 16);
        
        // 调试: 每 25 帧打印完整的 80-bit 帧
        if (frame % 25 == 0) {
            StringBuilder sb = new StringBuilder("[LTC-FRAME] frame=" + frame + " bits: ");
            for (int i = 0; i < 80; i++) {
                sb.append(bits[i] ? '1' : '0');
                if ((i + 1) % 8 == 0 && i < 79) sb.append(' ');
            }
            System.out.println(sb.toString());
        }
        
        return bits;
    }
    
    /**
     * 写入 BCD 值（LTC 线序：LSB first）
     * @param bits 目标数组
     * @param start 起始位置
     * @param value BCD 值
     * @param bitCount 位数
     */
    private void writeBcdLsbFirst(boolean[] bits, int start, int value, int bitCount) {
        for (int i = 0; i < bitCount; i++) {
            bits[start + i] = (value & (1 << i)) != 0;
        }
    }
    
    /**
     * 写入零
     */
    private void writeZeros(boolean[] bits, int start, int count) {
        for (int i = 0; i < count; i++) {
            bits[start + i] = false;
        }
    }
}
