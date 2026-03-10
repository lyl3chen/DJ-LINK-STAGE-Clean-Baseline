package dbclient.protocol;

import dbclient.connection.DbConnection;
import dbclient.packet.PacketBuilder;

/**
 * DB Server protocol handler
 * 
 * Status: PARTIALLY IMPLEMENTED
 * - GREETING: verified
 * - SETUP: verified  
 * - TRACK_REQUEST: verified
 * - RESPONSE parsing: NEEDS WORK (currently returns raw bytes)
 */
public class DbProtocol {
    
    private final DbConnection conn;
    private int transactionId = 1;
    private byte[] lastResponse;
    
    public DbProtocol(DbConnection conn) {
        this.conn = conn;
    }
    
    public byte[] getLastResponse() {
        return lastResponse;
    }
    
    /**
     * Send GREETING only, return response
     */
    public byte[] sendGreetingOnly() throws Exception {
        byte[] greeting = PacketBuilder.buildGreeting();
        conn.send(greeting);
        lastResponse = conn.receiveAll();
        return greeting;
    }
    
    /**
     * Send SETUP only, return request bytes
     */
    public byte[] sendSetupOnly(int playerNumber) throws Exception {
        byte[] setup = PacketBuilder.buildSetupRequest(playerNumber);
        conn.send(setup);
        lastResponse = conn.receiveAll();
        return setup;
    }
    
    /**
     * Perform full handshake: greeting + setup
     * @param playerNumber player number to pose as (1-4)
     * @return true if handshake successful
     */
    public boolean handshake(int playerNumber) throws Exception {
        // Step 1: Send greeting
        byte[] greeting = PacketBuilder.buildGreeting();
        System.out.println("  >> GREETING send: " + bytesToHex(greeting));
        conn.send(greeting);
        
        byte[] greetingResp = conn.receiveAll();
        System.out.println("  << GREETING recv: " + bytesToHex(greetingResp));
        
        if (greetingResp.length < 5 || greetingResp[0] != 0x11 || greetingResp[4] != 0x01) {
            System.out.println("  Greeting failed: " + bytesToHex(greetingResp));
            return false;
        }
        System.out.println("  Greeting OK");
        
        // Step 2: Send setup request
        byte[] setup = PacketBuilder.buildSetupRequest(playerNumber);
        System.out.println("  >> SETUP send: " + bytesToHex(setup));
        conn.send(setup);
        
        byte[] setupResp = conn.receiveAll();
        System.out.println("  << SETUP recv: " + bytesToHex(setupResp));
        
        // Check for 0x4000 response type (menu available)
        // Response format: MSG_START(5) + type(3) + length(3) + args
        if (setupResp.length > 10 && 
            setupResp[5] == 0x10 && setupResp[6] == 0x40 && setupResp[7] == 0x00) {
            System.out.println("  Session established");
            return true;
        }
        
        System.out.println("  Setup response: " + bytesToHex(setupResp));
        return false;
    }
    
    /**
     * Request track metadata
     * @param trackId track identifier
     * @param rekordboxId rekordbox database ID
     * @return raw response bytes
     */
    public byte[] requestTrackMetadata(int trackId, int rekordboxId) throws Exception {
        byte[] request = PacketBuilder.buildTrackMetadataRequest(trackId, rekordboxId);
        System.out.println("  >> TRACK send: " + bytesToHex(request));
        conn.send(request);
        
        byte[] response = conn.receiveAll();
        System.out.println("  << TRACK recv: " + bytesToHex(response));
        return response;
    }
    
    private String bytesToHex(byte[] b) {
        if (b == null || b.length == 0) return "empty";
        String s = "";
        for (int i = 0; i < Math.min(b.length, 64); i++) {
            s += String.format("%02x ", b[i]);
        }
        return s;
    }
}
