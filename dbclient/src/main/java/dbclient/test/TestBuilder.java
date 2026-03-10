package dbclient.test;

import dbclient.packet.PacketBuilder;

public class TestBuilder {
    public static void main(String[] args) throws Exception {
        byte[] setup = PacketBuilder.buildSetupRequest(3);
        System.out.println("Setup length: " + setup.length);
        System.out.print("Setup HEX: ");
        for (byte b : setup) {
            System.out.printf("%02x ", b);
        }
        System.out.println();
        
        byte[] track = PacketBuilder.buildTrackMetadataRequest(0x03010201, 131);
        System.out.println("Track length: " + track.length);
        System.out.print("Track HEX: ");
        for (byte b : track) {
            System.out.printf("%02x ", b);
        }
        System.out.println();
    }
}
