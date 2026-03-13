package dbclient.sync.drivers;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * MA2 Telnet 客户端（仅 BPM 场景最小实现）。
 */
public class Ma2TelnetClient {
    public static class CommandResult {
        public final String sentCommand;
        public final String rawResponse;
        public final String ack;
        public CommandResult(String sentCommand, String rawResponse, String ack) {
            this.sentCommand = sentCommand;
            this.rawResponse = rawResponse;
            this.ack = ack;
        }
    }

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private String host = "127.0.0.1";
    private int port = 30000;
    private String user = "remote";
    private String pass = "1234";

    private volatile boolean connected = false;
    private volatile String lastAck = "";

    public synchronized void configure(String host, int port, String user, String pass) {
        if (host != null && !host.isBlank()) this.host = host.trim();
        if (port > 0) this.port = port;
        if (user != null) this.user = user;
        if (pass != null) this.pass = pass;
    }

    public synchronized void connect() throws Exception {
        disconnect();
        System.out.println("[MA2] connecting " + host + ":" + port);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 2000);
        s.setSoTimeout(2200);
        this.socket = s;
        this.reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        System.out.println("[MA2] connected");

        // 读取连接后欢迎文本（原始）
        String banner = readRawWithin(420);
        System.out.println("[MA2] recv: " + banner.replace("\n", "\\n"));

        // 始终执行 login（包括空密码）
        String u = user == null ? "" : user;
        String p = pass == null ? "" : pass;
        String loginCmd = "login \"" + escapeForQuoted(u) + "\" \"" + escapeForQuoted(p) + "\"";
        String masked = "login \"" + escapeForQuoted(u) + "\" \"" + (p.isEmpty() ? "" : "***") + "\"";
        System.out.println("[MA2] send-login: " + masked);
        writer.write(loginCmd + "\r\n");
        writer.flush();

        String afterLoginRaw = readRawWithin(520);
        System.out.println("[MA2] login-response: " + afterLoginRaw.replace("\n", "\\n"));

        String loginAck = sanitizeAck(afterLoginRaw == null ? "" : afterLoginRaw);
        String lowAck = loginAck.toLowerCase();
        if (lowAck.contains("login needed") || lowAck.contains("please login") || lowAck.contains("error #43") || lowAck.contains("bad login") || lowAck.contains("invalid")) {
            connected = false;
            System.out.println("[MA2] login-failed");
            throw new IOException("login rejected: " + loginAck);
        }

        connected = true;
        lastAck = loginAck.isBlank() ? "login-ok" : loginAck;
        System.out.println("[MA2] login-ok");
    }

    public synchronized void disconnect() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        reader = null;
        writer = null;
        connected = false;
    }

    public synchronized String sendCommand(String cmd) throws Exception {
        CommandResult r = sendCommandDetailed(cmd);
        return r.ack;
    }

    public synchronized CommandResult sendCommandDetailed(String cmd) throws Exception {
        if (!connected || socket == null || writer == null) {
            throw new IOException("not connected");
        }
        writer.write(cmd + "\n");
        writer.flush();

        // 读取命令后的原始响应
        String raw = readRawWithin(300);
        String ack = sanitizeAck(raw == null ? "sent" : raw);
        if (ack == null || ack.isBlank()) ack = "sent";
        lastAck = ack;
        return new CommandResult(cmd, raw == null ? "" : raw, ack);
    }

    public synchronized boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized String getLastAck() { return lastAck; }

    private String tryReadLine() {
        try {
            if (reader == null) return null;
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readLatestAckWithin(long windowMs) {
        String raw = readRawWithin(windowMs);
        if (raw == null || raw.isBlank()) return null;
        String[] lines = raw.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i];
        }
        return null;
    }

    private String readRawWithin(long windowMs) {
        long end = System.currentTimeMillis() + Math.max(40, windowMs);
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < end) {
            try {
                if (reader == null || !reader.ready()) {
                    sleep(15);
                    continue;
                }
                int ch = reader.read();
                if (ch < 0) break;
                sb.append((char) ch);
            } catch (Exception ignored) {
                break;
            }
        }
        return sb.toString();
    }

    private static String sanitizeAck(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c) || c == '\n' || c == '\r' || c == '\t') sb.append(c);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.length() > 96) s = s.substring(s.length() - 96).trim();
        return s;
    }

    private static String escapeForQuoted(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
