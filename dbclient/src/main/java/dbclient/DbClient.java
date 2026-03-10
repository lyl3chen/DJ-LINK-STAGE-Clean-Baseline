package dbclient;

import java.io.*;
import java.net.*;

public class DbClient {
    public static void main(String[] args) throws Exception {
        String ip = "192.168.100.132";
        int port = 48304;
        
        System.out.println("=== DB Client === " + ip + ":" + port + "\n");
        
        Socket s = new Socket();
        s.setSoTimeout(5000);
        s.connect(new InetSocketAddress(ip, port), 5000);
        System.out.println("Connected: " + s.getLocalPort() + " -> " + ip + ":" + port + "\n");
        
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        
        // GREETING
        System.out.println("GREETING...");
        byte[] g = {0x11, 0x00, 0x00, 0x00, 0x01};
        out.write(g); out.flush();
        
        byte[] gr = new byte[5];
        int r = in.read(gr);
        System.out.println("Response: " + toHex(gr));
        System.out.println("OK\n");
        
        // SETUP
        System.out.println("SETUP...");
        byte[] setup = buildSetup(3);
        out.write(setup); out.flush();
        
        byte[] sr = readAll(in);
        System.out.println("Response: " + toHex(sr));
        System.out.println("Session OK\n");
        
        // TRACK REQUEST
        System.out.println("TRACK REQUEST...");
        byte[] tr = buildTrack(0x03010201, 131);
        out.write(tr); out.flush();
        
        byte[] trr = readAll(in);
        System.out.println("Response: " + toHex(trr));
        System.out.println("\nDone");
        
        s.close();
    }
    
    static byte[] buildSetup(int p) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[]{0x11, (byte)0x87, 0x23, 0x49, (byte)0xae});
        b.write(new byte[]{0x11, (byte)0xff, (byte)0xff, (byte)0xff, (byte)0xfe});
        b.write(new byte[]{0x10, 0x00, 0x00});
        b.write(0x0f); b.write(0x01);
        b.write(new byte[]{0x14, 0x00, 0x00, 0x00, 0x0c, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        b.write(0x11); b.write((byte)(p>>24)); b.write((byte)(p>>16)); b.write((byte)(p>>8)); b.write((byte)p);
        return b.toByteArray();
    }
    
    static byte[] buildTrack(int tid, int rid) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(new byte[]{0x11, 0x00, 0x00, 0x00, 0x01});
        b.write(new byte[]{0x11, 0x00, (byte)0x20, 0x02});
        b.write(new byte[]{0x10, 0x00, 0x00});
        b.write(0x0f); b.write(0x02);
        b.write(new byte[]{0x14, 0x00, 0x00, 0x00, 0x0c, 0x06, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        b.write(0x11); b.write((byte)(tid>>24)); b.write((byte)(tid>>16)); b.write((byte)(tid>>8)); b.write((byte)tid);
        b.write(0x11); b.write((byte)(rid>>24)); b.write((byte)(rid>>16)); b.write((byte)(rid>>8)); b.write((byte)rid);
        return b.toByteArray();
    }
    
    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        try {
            while (true) {
                int n = in.read(buf);
                if (n == -1) break;
                b.write(buf, 0, n);
                Thread.sleep(30);
                if (in.available() == 0) break;
            }
        } catch (Exception e) {}
        return b.toByteArray();
    }
    
    static String toHex(byte[] b) {
        String s = "";
        for (int i = 0; i < Math.min(b.length, 64); i++) s += String.format("%02x ", b[i]);
        if (b.length > 64) s += "...";
        return s;
    }
}
