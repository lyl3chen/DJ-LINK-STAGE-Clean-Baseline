package dbclient.sync.drivers;

import org.deepsymmetry.libcarabiner.Message;
import org.deepsymmetry.libcarabiner.Runner;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * lib-carabiner 引擎封装（中文说明）：
 * 1) 负责启动/停止 Carabiner Runner；
 * 2) 通过本地 TCP（默认 127.0.0.1:17000）收发文本协议消息；
 * 3) 将 status/version 等消息解析成前端可读状态；
 * 4) 在断管（Broken pipe）或读异常时自动重连，避免界面长期报错。
 */
public class CarabinerLinkEngine {
    // 生命周期状态
    private enum LifecycleState { STARTING, RUNNING, RECOVERING, FAILED, STOPPED }
    private volatile LifecycleState lifecycleState = LifecycleState.STOPPED;
    
    private final Runner runner = Runner.getInstance();

    private volatile boolean running = false;
    private volatile boolean supported = false;
    private volatile String error = "";
    private volatile String lastMessageType = "";
    private volatile long lastUpdateTs = 0L;
    private volatile double tempo = 120.0;
    private volatile double carabinerBeatPosition = 0.0;
    private volatile boolean playing = false;
    private volatile int numPeers = 0;
    private volatile String version = "";
    private volatile double desiredTempo = 120.0;
    private volatile double desiredBeatPosition = 0.0;
    private volatile boolean desiredPlaying = false;
    private volatile long carabinerStartRaw = 0L;
    private volatile double lastSentTempo = -1.0;
    private volatile double lastSentBeatPosition = Double.NaN;
    private volatile boolean statusSeen = false;
    private volatile boolean versionSeen = false;
    private volatile boolean startStopSyncEnabled = false;
    private volatile int lastPeersSeen = -1;
    private volatile boolean forceTempoPush = true;
    private volatile boolean strictTempoMaster = true;
    private volatile boolean hadPeersBefore = false;
    private volatile long peersZeroSince = 0L;
    private volatile long lastRunnerRestartAt = 0L;

    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private Thread readThread;
    private Thread pingThread;
    
    private final Object lifecycleLock = new Object();
    private volatile boolean inReconnect = false; // 防止重入

    private int port = 17000;
    private int updateIntervalMs = 20;
    private volatile long lastReconnectAttempt = 0L;
    private volatile long connectRefusedSince = 0L;

    /**
     * 启动 Carabiner：
     * - 先做平台可运行检查；
     * - 再设置端口和更新间隔；
     * - 最后异步连接 TCP，避免阻塞保存配置接口。
     */
    @SuppressWarnings("unchecked")
    public synchronized void start(Map<String, Object> cfg) {
        supported = runner.canRunCarabiner();
        if (!supported) {
            running = false;
            lifecycleState = LifecycleState.FAILED;
            error = "carabiner not supported on this platform";
            return;
        }
        port = intCfg(cfg, "port", 17000);
        updateIntervalMs = intCfg(cfg, "updateIntervalMs", 20);

        try {
            // 使用 CarabinerProcessManager 启动，确保单实例
            CarabinerProcessManager.start(runner, port, updateIntervalMs);
            
            running = true;
            lifecycleState = LifecycleState.STARTING;
            error = "";
            new Thread(() -> {
                try {
                    connectAndListen();
                    error = "";
                    connectRefusedSince = 0L;
                } catch (Exception e) {
                    if (running) setConnectError("carabiner start failed", e);
                }
            }, "carabiner-connect").start();
        } catch (Exception e) {
            running = false;
            error = "carabiner start failed: " + e.getMessage();
        }
    }

    public synchronized void stop() {
        running = false;
        lifecycleState = LifecycleState.STOPPED;
        
        // 使用 CarabinerProcessManager 停止，确保完全退出
        CarabinerProcessManager.stop(runner);
        
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        reader = null;
        writer = null;
        statusSeen = false;
        versionSeen = false;
        startStopSyncEnabled = false;
        
        if (readThread != null) readThread.interrupt();
        if (pingThread != null) pingThread.interrupt();
    }

    /**
     * 接收上游（SyncOutputManager）派生出来的播放源状态：
     * - bpm：当前应输出给 Link 的目标 BPM（已按当前业务逻辑计算）
     * - beatPosition：当前拍点位置（用于 beat/phase 同步）
     * - playing：统一播放态（用于避免 Carabiner 内部 start 字段和界面语义冲突）
     */
    public synchronized void updateFromSource(Double bpm, Boolean playing) {
        if (bpm != null && bpm > 0 && Double.isFinite(bpm)) desiredTempo = bpm;
        if (playing != null) desiredPlaying = playing;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("lifecycleState", lifecycleState.toString());
        m.put("carabinerSupported", supported);
        m.put("carabinerRunning", running);
        m.put("carabinerEnabled", running && statusSeen);
        m.put("carabinerStatusSeen", statusSeen);
        m.put("carabinerVersionSeen", versionSeen);
        m.put("carabinerStartStopSyncEnabled", startStopSyncEnabled);
        m.put("tempo", tempo);
        // 对外默认展示跟随源的 beat 位置，避免 Carabiner 内部时间轴（可能为负）造成误解。
        m.put("beatPosition", desiredBeatPosition);
        m.put("carabinerBeatPosition", carabinerBeatPosition);
        m.put("playing", playing);
        m.put("numPeers", numPeers);
        m.put("version", version);
        m.put("lastMessageType", lastMessageType);
        m.put("lastUpdateTs", lastUpdateTs);
        m.put("carabinerStartRaw", carabinerStartRaw);
        if (running && numPeers == 0) {
            m.put("linkNotice", "Carabiner is running, but no Link peers discovered yet.");
        } else {
            m.put("linkNotice", "");
        }
        m.put("error", error);
        // 连接诊断
        m.put("socketConnected", socket != null && socket.isConnected() && !socket.isClosed());
        m.put("readThreadAlive", readThread != null && readThread.isAlive());
        m.put("inReconnect", inReconnect);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void onLine(String line) {
        try {
            if (line == null || line.isBlank()) return;
            String trimmed = line.trim();
            if (trimmed.startsWith("bad-")) {
                lastMessageType = trimmed;
                lastUpdateTs = System.currentTimeMillis();
                error = "carabiner command rejected: " + trimmed;
                return;
            }

            Message m = new Message(trimmed);
            lastMessageType = m.messageType;
            lastUpdateTs = System.currentTimeMillis();

            if ("version".equals(m.messageType) && m.details instanceof String) {
                version = (String) m.details;
                versionSeen = true;
            }

            if ("unsupported".equals(m.messageType)) {
                // 仅记录，不中断主链路。
                lastMessageType = "unsupported";
            }

            if (("status".equals(m.messageType) || "beat-at-time".equals(m.messageType) || "phase-at-time".equals(m.messageType))
                    && m.details instanceof Map) {
                if ("status".equals(m.messageType)) statusSeen = true;
                Map<String, Object> details = (Map<String, Object>) m.details;
                Object bpm = details.get("bpm");
                Object beat = details.get("beat");
                Object peers = details.get("peers");
                Object start = details.get("start");
                if (bpm instanceof Number) {
                    tempo = ((Number) bpm).doubleValue();
                    // 严格主导：若会话 tempo 偏离 CDJ 目标值，触发下一轮强制回推。
                    if (strictTempoMaster && Math.abs(tempo - desiredTempo) >= 0.05) {
                        forceTempoPush = true;
                    }
                }
                if (beat instanceof Number) carabinerBeatPosition = ((Number) beat).doubleValue();
                if (peers instanceof Number) {
                    int p = ((Number) peers).intValue();
                    numPeers = p;
                    if (p > 0) {
                        hadPeersBefore = true;
                        peersZeroSince = 0L;
                    } else if (hadPeersBefore && peersZeroSince == 0L) {
                        // 出现“曾经有peer，后来归零”的场景，记录时间用于后续自动重入。
                        peersZeroSince = System.currentTimeMillis();
                    }
                    if (p != lastPeersSeen) {
                        lastPeersSeen = p;
                        // peer 拓扑变化后强制下一轮推一次 tempo，避免对端重入会话后卡在旧值（如120）。
                        forceTempoPush = true;
                    }
                }
                if (start instanceof Number) carabinerStartRaw = ((Number) start).longValue();
                // 统一跟随系统播放源语义，避免与 sourcePlaying 产生误导性分叉。
                playing = desiredPlaying;
            }
            error = "";
        } catch (Exception e) {
            error = "carabiner message parse failed: " + e.getMessage();
        }
    }

    /**
     * 尝试连接 Carabiner TCP 端口并拉起读写线程。
     * 这里采用重试窗口（次数+间隔），用于覆盖“进程刚启动端口未就绪”的瞬态。
     */
    private void connectAndListen() throws Exception {
        IOException last = null;
        for (int i = 0; i < 100; i++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 800);
                s.setTcpNoDelay(true);
                socket = s;
                reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                startThreads();
                synchronized (lifecycleLock) {
                    if (lifecycleState != LifecycleState.STOPPED) {
                        lifecycleState = LifecycleState.RUNNING;
                        System.out.println("[CarabinerLinkEngine] connectAndListen: CONNECTED, lifecycle -> RUNNING");
                    }
                }
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            }
        }
        throw new IOException("unable to connect to carabiner port " + port + ": " + (last == null ? "unknown" : last.getMessage()));
    }

    /**
     * 启动两个线程：
     * - readThread：持续读取 Carabiner 输出并解析
     * - pingThread：周期发送 status/version/bpm（bpm 做变化阈值控制）
     */
    private void startThreads() {
        readThread = new Thread(() -> {
            try {
                String line;
                while (running && reader != null) {
                    try {
                        line = reader.readLine();
                        if (line == null) {
                            // Socket closed by remote
                            if (running) {
                                error = "carabiner socket closed by remote";
                                System.out.println("[CarabinerLinkEngine] readThread: socket closed by remote (readLine returned null), calling tryReconnect()");
                                tryReconnect();
                            }
                            break;
                        }
                        onLine(line.trim());
                    } catch (IOException e) {
                        if (running) {
                            error = "carabiner read failed: " + e.getMessage();
                            System.out.println("[CarabinerLinkEngine] readThread: IOException=" + e.getMessage() + ", calling tryReconnect()");
                            tryReconnect();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (running) {
                    error = "carabiner read thread exception: " + e.getMessage();
                    System.out.println("[CarabinerLinkEngine] readThread: exception=" + e.getMessage() + ", calling tryReconnect()");
                    tryReconnect();
                }
            }
        }, "carabiner-read");
        readThread.setDaemon(true);
        readThread.start();

        pingThread = new Thread(() -> {
            boolean initSent = false;
            long lastSyncEnableAt = 0L;
            long lastForcedTempoAt = 0L;
            long lastForcedBeatAt = 0L;
            while (running && writer != null) {
                try {
                    long now = System.currentTimeMillis();
                    if (!initSent || (now - lastSyncEnableAt) > 3000) {
                        // 周期重发，避免对端后加入时会话能力状态不一致。
                        writer.write("enable-start-stop-sync\n");
                        initSent = true;
                        startStopSyncEnabled = true;
                        lastSyncEnableAt = now;
                    }
                    writer.write("status\n");
                    writer.write("version\n");
                    // 提高 BPM 推送频率并仅在变化时发送；在 peer 变化后强制推送一次。
                    boolean tempoChanged = (lastSentTempo < 0 || Math.abs(desiredTempo - lastSentTempo) >= 0.01);
                    boolean forceByPeerChange = forceTempoPush;
                    boolean forceByPeriodic = (now - lastForcedTempoAt) > 500;
                    if (tempoChanged || forceByPeerChange || forceByPeriodic) {
                        writer.write(String.format(java.util.Locale.US, "bpm %.3f\n", desiredTempo));
                        lastSentTempo = desiredTempo;
                        forceTempoPush = false;
                        lastForcedTempoAt = now;
                    }

                    // Beat/Phase 同步：周期发送 beat-at-time。
                    // when 使用当前系统时间（微秒），quantum 先固定 4。
                    boolean beatChanged = Double.isNaN(lastSentBeatPosition)
                            || Math.abs(desiredBeatPosition - lastSentBeatPosition) >= 0.02;
                    boolean beatPeriodic = (now - lastForcedBeatAt) > 1000;
                    if (beatChanged || beatPeriodic) {
                        long whenUs = now * 1000L;
                        // quantum 用浮点格式，避免 Carabiner 返回 bad-quantum。
                        writer.write(String.format(java.util.Locale.US, "force-beat-at-time %.6f %d 4\n", desiredBeatPosition, whenUs));
                        lastSentBeatPosition = desiredBeatPosition;
                        lastForcedBeatAt = now;
                    }

                    writer.flush();

                    // 自动重入：若曾有 peer，随后长期为 0（常见于对端手动关再开 Link），
                    // 主动重启一次 Runner 会话，避免需要手工重启本项目 AbletonLink。
                    if (hadPeersBefore && numPeers == 0 && peersZeroSince > 0
                            && (now - peersZeroSince) > 1500
                            && (now - lastRunnerRestartAt) > 5000) {
                        lastRunnerRestartAt = now;
                        restartRunnerSession();
                    }
                } catch (Exception e) {
                    if (running) {
                        error = "carabiner write failed: " + e.getMessage();
                        System.out.println("[CarabinerLinkEngine] pingThread: write exception=" + e.getMessage() + ", calling tryReconnect()");
                        tryReconnect();
                    }
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }, "carabiner-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    /**
     * 自动重连：
     * - 1秒节流，避免连续异常时刷爆重连；
     * - 重连成功后清空 error；
     * - 失败则保留最新错误信息供 UI 诊断。
     */
    private synchronized void tryReconnect() {
        // 防止重入
        if (inReconnect) {
            System.out.println("[CarabinerLinkEngine] tryReconnect: SKIP (already in progress), lifecycle=" + lifecycleState);
            return;
        }
        inReconnect = true;
        
        try {
            long now = System.currentTimeMillis();
            if (now - lastReconnectAttempt < 1000) {
                System.out.println("[CarabinerLinkEngine] tryReconnect: SKIP (throttled 1s), lastAttempt=" + (now - lastReconnectAttempt) + "ms ago");
                return;
            }
            lastReconnectAttempt = now;
            
            // 记录当前状态诊断信息
            boolean socketWasConnected = socket != null && socket.isConnected() && !socket.isClosed();
            boolean readThreadAlive = readThread != null && readThread.isAlive();
            System.out.println("[CarabinerLinkEngine] tryReconnect: START, lifecycle=" + lifecycleState + ", socketWasConnected=" + socketWasConnected + ", readThreadAlive=" + readThreadAlive);
            
            // 设置状态为 RECOVERING
            synchronized (lifecycleLock) {
                if (lifecycleState == LifecycleState.RUNNING) {
                    lifecycleState = LifecycleState.RECOVERING;
                    System.out.println("[CarabinerLinkEngine] Lifecycle: RUNNING -> RECOVERING");
                }
            }
            
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
            socket = null;
            reader = null;
            writer = null;

            try {
                connectAndListen();
                error = "";
                connectRefusedSince = 0L;
                System.out.println("[CarabinerLinkEngine] tryReconnect: SUCCESS, lifecycle -> RUNNING");
            } catch (Exception e) {
                synchronized (lifecycleLock) {
                    if (lifecycleState != LifecycleState.STOPPED) {
                        lifecycleState = LifecycleState.FAILED;
                    }
                }
                System.out.println("[CarabinerLinkEngine] tryReconnect: FAILED, lifecycle -> FAILED, error=" + e.getMessage());
                setConnectError("carabiner reconnect failed", e);
            }
        } finally {
            inReconnect = false;
        }
    }

    /**
     * 对端 Link 反复关开后，主动重启一次 Runner 会话来恢复跨机会话可见性。
     */
    private synchronized void restartRunnerSession() {
        System.out.println("[CarabinerLinkEngine] restartRunnerSession: restarting Carabiner...");
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        socket = null;
        reader = null;
        writer = null;
        
        // 使用 CarabinerProcessManager 重启，确保单实例
        try {
            CarabinerProcessManager.stop(runner);
            Thread.sleep(500);
            CarabinerProcessManager.start(runner, port, updateIntervalMs);
            connectAndListen();
            forceTempoPush = true;
            peersZeroSince = 0L;
            error = "";
            System.out.println("[CarabinerLinkEngine] restartRunnerSession: success");
        } catch (Exception e) {
            error = "carabiner session restart failed: " + e.getMessage();
            System.err.println("[CarabinerLinkEngine] restartRunnerSession: failed - " + e.getMessage());
        }
    }

    private void setConnectError(String prefix, Exception e) {
        String msg = String.valueOf(e.getMessage());
        boolean refused = msg != null && msg.toLowerCase().contains("connection refused");
        long now = System.currentTimeMillis();
        if (refused) {
            if (connectRefusedSince == 0L) connectRefusedSince = now;
            // 连接拒绝短暂抖动期（启动/重启窗口）不立刻标红，避免 UI 闪烁误报。
            if (now - connectRefusedSince < 4000) {
                error = "";
                return;
            }
        } else {
            connectRefusedSince = 0L;
        }
        error = prefix + ": " + msg;
    }

    private static int intCfg(Map<String, Object> cfg, String key, int def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }
}
