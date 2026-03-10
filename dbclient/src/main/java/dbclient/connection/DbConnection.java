package dbclient.connection;

import java.io.*;
import java.net.*;

/**
 * TCP connection to Pioneer DB Server
 * 
 * Status: CLEAN-ROOM IMPLEMENTED
 * Based on captured protocol data, not beat-link source
 */
public class DbConnection {
    private final String host;
    private final int port;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    
    public DbConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }
    
    /**
     * Connect to DB server
     */
    public void connect() throws IOException {
        socket = new Socket();
        socket.setSoTimeout(5000);
        socket.connect(new InetSocketAddress(host, port), 5000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }
    
    /**
     * Disconnect from DB server
     */
    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (IOException e) {}
        }
    }
    
    /**
     * Send raw bytes
     */
    public void send(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }
    
    /**
     * Read all available bytes (with small delays to allow full response)
     */
    public byte[] receiveAll() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        
        try {
            while (true) {
                int r = in.read(buffer);
                if (r == -1) break;
                baos.write(buffer, 0, r);
                Thread.sleep(30);
                if (in.available() == 0) break;
            }
        } catch (InterruptedException e) {
            // Ignore
        }
        
        return baos.toByteArray();
    }
    
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
    
    public String getHost() { return host; }
    public int getPort() { return port; }
}
