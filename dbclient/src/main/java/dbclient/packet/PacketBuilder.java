package dbclient.packet;

import java.io.*;

/**
 * Protocol packet builders for Pioneer DB Server
 * 
 * Status: CLEAN-ROOM IMPLEMENTED
 * Based on captured protocol data from beat-link debug logs
 */
public class PacketBuilder {

    private static final byte MSG_START_1 = 0x11;
    private static final byte MSG_START_2 = (byte)0x87;
    private static final byte MSG_START_3 = 0x23;
    private static final byte MSG_START_4 = (byte)0x49;
    private static final byte MSG_START_5 = (byte)0xae;
    
    /**
     * Build GREETING packet
     * Format: 0x11 0x00 0x00 0x00 0x01
     * (NumberField: type=0x11 for 4-byte, value=1)
     */
    public static byte[] buildGreeting() {
        return new byte[]{0x11, 0x00, 0x00, 0x00, 0x01};
    }
    
    /**
     * Build SETUP_REQUEST packet
     * 
     * Format:
     *   MESSAGE_START:    11 87 23 49 ae
     *   Transaction:      11 ff ff ff fe  (4-byte, value=0xfffffffe)
     *   MessageType:     10 00 00       (2-byte, value=0x0000 = SETUP_REQ)
     *   Arg count:       0f 01
     *   Binary padding:  14 00 00 00 0c 06 00 00 00 00 00 00 00 00 00 00 00
     *   Player number:  11 00 00 00 XX
     */
    public static byte[] buildSetupRequest(int playerNumber) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        
        // MESSAGE_START
        b.write(new byte[]{MSG_START_1, MSG_START_2, MSG_START_3, MSG_START_4, MSG_START_5});
        
        // Transaction: 0xfffffffe (4-byte)
        b.write(new byte[]{0x11, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xfe});
        
        // MessageType: 0x0000 SETUP_REQ (2-byte) - 修正
        b.write(new byte[]{0x10, 0x00, 0x00});
        
        // Arg count: 2 (1-byte) - 修正
        b.write(0x0f);
        b.write(0x02);
        
        // BinaryField padding (16 bytes) - 修改 06 00 → 06 06
        b.write(new byte[]{0x14, 0x00, 0x00, 0x00, 0x0c, 0x06, 0x06, 0x00, 
                          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        
        // Player number (4 bytes, big-endian)
        b.write(0x11);
        b.write((byte)(playerNumber >> 24));
        b.write((byte)(playerNumber >> 16));
        b.write((byte)(playerNumber >> 8));
        b.write((byte)playerNumber);
        
        return b.toByteArray();
    }
    
    /**
     * Build TRACK_METADATA_REQUEST packet
     * 
     * Format:
     *   Transaction:      11 00 00 00 01  (4-byte, value=1)
     *   MessageType:     10 00 20 02    (2-byte, value=0x2002 = TRACK_METADATA)
     *   Arg count:       0f 02
     *   Binary padding:  14 00 00 00 0c 06 06 00 00 00 00 00 00 00 00 00 00
     *   Track ID:        11 TT TT TT TT
     *   Rekordbox ID:    11 RR RR RR RR
     */
    public static byte[] buildTrackMetadataRequest(int trackId, int rekordboxId) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        
        // Transaction ID = 1 (4-byte)
        b.write(new byte[]{0x11, 0x00, 0x00, 0x00, 0x01});
        
        // MessageType: 0x2002 TRACK_METADATA (2-byte) - 修正
        b.write(new byte[]{0x10, 0x00, (byte)0x20, 0x02});
        
        // Arg count: 2 (1-byte)
        b.write(0x0f);
        b.write(0x02);
        
        // BinaryField padding (16 bytes)
        b.write(new byte[]{0x14, 0x00, 0x00, 0x00, 0x0c, 0x06, 0x06, 0x00, 
                          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        
        // Track ID (4 bytes, big-endian)
        b.write(0x11);
        b.write((byte)(trackId >> 24));
        b.write((byte)(trackId >> 16));
        b.write((byte)(trackId >> 8));
        b.write((byte)trackId);
        
        // Rekordbox ID (4 bytes, big-endian)
        b.write(0x11);
        b.write((byte)(rekordboxId >> 24));
        b.write((byte)(rekordboxId >> 16));
        b.write((byte)(rekordboxId >> 8));
        b.write((byte)rekordboxId);
        
        return b.toByteArray();
    }
}
