package dbclient.sync.drivers;

/**
 * LtcFrameEncoder - 80-bit SMPTE LTC 帧编码器
 */
public class LtcFrameEncoder {
    
    private final double frameRate;
    
    public LtcFrameEncoder(double frameRate) {
        this.frameRate = frameRate;
    }
    
    /**
     * 将帧号编码为 80-bit LTC 帧
     * 
     * @param frame 帧号
     * @return 80-bit 布尔数组（bit 0 先传）
     */
    public boolean[] buildFrame(long frame) {
        // 处理负帧
        if (frame < 0) frame = 0;
        
        // 转换为 hh:mm:ss:ff
        long totalFrames = frame;
        int ff = (int) (totalFrames % (int) frameRate);
        long totalSeconds = totalFrames / (int) frameRate;
        int ss = (int) (totalSeconds % 60);
        int mm = (int) ((totalSeconds / 60) % 60);
        int hh = (int) ((totalSeconds / 3600) % 24);
        
        boolean[] bits = new boolean[80];
        
        // Sync Word: 0x3FFD (bits 0-15)
        // 0x3FFD = 0011 1111 1111 1101 (MSB first)
        // LSB first transmission
        bits[0] = true;   // bit 0 (LSB of 0xD)
        bits[1] = false;  // bit 1
        bits[2] = true;   // bit 2
        bits[3] = true;   // bit 3 (MSB of 0xD)
        // bits 4-13 = all 1s
        for (int i = 4; i < 14; i++) {
            bits[i] = true;
        }
        // bit 14 = 0
        bits[14] = false;
        // bit 15 = 0 (MSB of 0x3FFD)
        bits[15] = false;
        
        // Frames - Units (bits 16-19)
        setBcd(bits, 16, 4, ff % 10);
        
        // Frames - Tens (bits 20-23)
        setBcd(bits, 20, 4, ff / 10);
        
        // Seconds - Units (bits 24-27)
        setBcd(bits, 24, 4, ss % 10);
        
        // Seconds - Tens (bits 28-31)
        setBcd(bits, 28, 4, ss / 10);
        
        // Minutes - Units (bits 32-35)
        setBcd(bits, 32, 4, mm % 10);
        
        // Minutes - Tens (bits 36-39)
        setBcd(bits, 36, 4, mm / 10);
        
        // Hours - Units (bits 40-43)
        setBcd(bits, 40, 4, hh % 10);
        
        // Hours - Tens (bits 44-47)
        setBcd(bits, 44, 4, hh / 10);
        
        // User Bits (bits 48-63) - 全 0
        // Parity bit (67) - 偶校验
        boolean parity = false;
        for (int i = 0; i < 64; i++) {
            parity ^= bits[i];
        }
        bits[67] = parity;
        
        // Reserved bits (bits 70-79) - all 1s
        for (int i = 70; i < 80; i++) {
            bits[i] = true;
        }
        
        return bits;
    }
    
    private void setBcd(boolean[] bits, int start, int bitsCount, int value) {
        for (int i = 0; i < bitsCount; i++) {
            bits[start + i] = (value & (1 << i)) != 0;
        }
    }
}
