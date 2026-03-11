package dbclient.sync.drivers;

import com.google.gson.Gson;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小 bridge 客户端：Java <-> Link bridge 通过 localhost UDP JSON 通信。
 */
public class AbletonLinkBridgeClient {
    private static final Gson GSON = new Gson();

    private final String host;
    private final int sendPort;
    private final int listenPort;
    private DatagramSocket sendSocket;
    private DatagramSocket recvSocket;
    private Thread recvThread;
    private volatile boolean running = false;

    private volatile boolean bridgeRunning = false;
    private volatile int numPeers = 0;
    private volatile long lastAckTs = 0L;
    private volatile String error = "";
    private volatile String backendMode = "ack-only";
    private volatile boolean backendLoaded = false;
    private volatile String backendVersion = "";
    private volatile String peerDetectionWorking = "unknown";
    private volatile String backendInitError = "";
    private volatile int maxPeersSeen = 0;
    private volatile long lastPeerChangeTs = 0L;
    private volatile long peerSampleCount = 0L;

    public AbletonLinkBridgeClient(String host, int sendPort, int listenPort) {
        this.host = host;
        this.sendPort = sendPort;
        this.listenPort = listenPort;
    }

    public synchronized void start() {
        if (running) return;
        try {
            sendSocket = new DatagramSocket();
            recvSocket = new DatagramSocket(listenPort, InetAddress.getByName("127.0.0.1"));
            running = true;
            recvThread = new Thread(this::recvLoop, "link-bridge-recv");
            recvThread.setDaemon(true);
            recvThread.start();
            error = "";
        } catch (Exception e) {
            running = false;
            error = "bridge socket start failed: " + e.getMessage();
        }
    }

    public synchronized void stop() {
        running = false;
        bridgeRunning = false;
        if (recvSocket != null) {
            try { recvSocket.close(); } catch (Exception ignored) {}
            recvSocket = null;
        }
        if (sendSocket != null) {
            try { sendSocket.close(); } catch (Exception ignored) {}
            sendSocket = null;
        }
        if (recvThread != null) {
            try { recvThread.join(200); } catch (InterruptedException ignored) {}
            recvThread = null;
        }
    }

    public void sendSync(double tempo, double beatPosition, boolean playing, long timestamp,
                         Integer sourcePlayer, String sourceState) {
        if (!running || sendSocket == null) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "sync");
            payload.put("tempo", tempo);
            payload.put("beatPosition", beatPosition);
            payload.put("playing", playing);
            payload.put("timestamp", timestamp);
            payload.put("sourcePlayer", sourcePlayer);
            payload.put("sourceState", sourceState);
            String txt = GSON.toJson(payload);
            byte[] data = txt.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length, InetAddress.getByName(host), sendPort);
            sendSocket.send(pkt);
        } catch (Exception e) {
            error = "bridge send failed: " + e.getMessage();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bridgeRunning", bridgeRunning);
        m.put("numPeers", numPeers);
        m.put("lastAckTs", lastAckTs);
        m.put("error", error);
        m.put("backendMode", backendMode);
        m.put("backendLoaded", backendLoaded);
        m.put("backendVersion", backendVersion);
        m.put("peerDetectionWorking", peerDetectionWorking);
        m.put("backendInitError", backendInitError);
        m.put("maxPeersSeen", maxPeersSeen);
        m.put("lastPeerChangeTs", lastPeerChangeTs);
        m.put("peerSampleCount", peerSampleCount);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void recvLoop() {
        byte[] buf = new byte[2048];
        while (running && recvSocket != null) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                recvSocket.receive(pkt);
                String txt = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
                Map<String, Object> m = GSON.fromJson(txt, Map.class);
                if (m == null) continue;
                Object r = m.get("running");
                Object p = m.get("numPeers");
                Object t = m.get("lastAckTs");
                Object e = m.get("error");
                Object bm = m.get("backendMode");
                Object bl = m.get("backendLoaded");
                Object bv = m.get("backendVersion");
                Object pd = m.get("peerDetectionWorking");
                Object bie = m.get("backendInitError");
                Object mps = m.get("maxPeersSeen");
                Object lpct = m.get("lastPeerChangeTs");
                Object psc = m.get("peerSampleCount");
                bridgeRunning = Boolean.TRUE.equals(r);
                if (p instanceof Number) numPeers = ((Number) p).intValue();
                if (t instanceof Number) lastAckTs = ((Number) t).longValue();
                error = e == null ? "" : String.valueOf(e);
                backendMode = bm == null ? backendMode : String.valueOf(bm);
                backendLoaded = Boolean.TRUE.equals(bl);
                backendVersion = bv == null ? "" : String.valueOf(bv);
                peerDetectionWorking = pd == null ? "unknown" : String.valueOf(pd);
                backendInitError = bie == null ? "" : String.valueOf(bie);
                if (mps instanceof Number) maxPeersSeen = ((Number) mps).intValue();
                if (lpct instanceof Number) lastPeerChangeTs = ((Number) lpct).longValue();
                if (psc instanceof Number) peerSampleCount = ((Number) psc).longValue();
            } catch (Exception ex) {
                if (running) error = "bridge recv failed: " + ex.getMessage();
            }
        }
    }
}
