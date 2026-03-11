package dbclient.sync.drivers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ableton Link bridge 子进程控制器（增强可观测版）。
 */
public class AbletonLinkBridgeProcess {
    private volatile Process process;
    private volatile long startedAt = 0L;
    private volatile String lastError = "";

    private volatile Integer lastExitCode = null;
    private volatile String lastExitReason = "";
    private volatile String lastStdout = "";
    private volatile String lastStderr = "";

    private volatile String backendMode = "ack-only";
    private volatile boolean backendLoaded = false;
    private volatile String backendInitError = "";

    private Thread outThread;
    private Thread errThread;

    public synchronized Map<String, Object> start() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (isRunning()) {
                out.put("ok", true);
                out.put("message", "already running");
                out.putAll(status());
                return out;
            }

            String userDir = System.getProperty("user.dir", ".");
            File workDir = new File(userDir);
            File script = new File(workDir, "../scripts/ableton-link-bridge.js");
            if (!script.exists()) {
                lastError = "bridge script not found: " + script.getAbsolutePath();
                out.put("ok", false);
                out.put("error", lastError);
                out.putAll(status());
                return out;
            }

            ProcessBuilder pb = new ProcessBuilder("node", script.getAbsolutePath());
            pb.directory(workDir);
            // 不 merge，分别抓 stdout/stderr，便于 API 回传退出原因。
            pb.redirectErrorStream(false);

            process = pb.start();
            startedAt = System.currentTimeMillis();
            lastError = "";
            lastExitCode = null;
            lastExitReason = "";
            lastStdout = "";
            lastStderr = "";
            backendMode = "ack-only";
            backendLoaded = false;
            backendInitError = "";

            startPumpThreads(process);

            // 给子进程一点启动时间，避免“瞬间退出还显示 started”
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (!process.isAlive()) {
                int ec = process.exitValue();
                lastExitCode = ec;
                lastExitReason = "bridge exited immediately";
                lastError = "bridge exited immediately (code=" + ec + ")";
                out.put("ok", false);
                out.put("error", lastError);
                out.putAll(status());
                return out;
            }

            out.put("ok", true);
            out.put("message", "started");
            out.putAll(status());
            return out;
        } catch (Exception e) {
            lastError = "bridge start failed: " + e.getMessage();
            out.put("ok", false);
            out.put("error", lastError);
            out.putAll(status());
            return out;
        }
    }

    public synchronized Map<String, Object> stop() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (process == null || !process.isAlive()) {
                process = null;
                out.put("ok", true);
                out.put("message", "already stopped");
                out.putAll(status());
                return out;
            }
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {}
            if (process.isAlive()) process.destroyForcibly();
            if (process != null) {
                try {
                    lastExitCode = process.exitValue();
                } catch (Exception ignored) {}
            }
            lastExitReason = "stopped by API";
            process = null;
            startedAt = 0L;
            out.put("ok", true);
            out.put("message", "stopped");
            out.putAll(status());
            return out;
        } catch (Exception e) {
            lastError = "bridge stop failed: " + e.getMessage();
            out.put("ok", false);
            out.put("error", lastError);
            out.putAll(status());
            return out;
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean running = isRunning();
        m.put("bridgeRunning", running);
        m.put("bridgePid", running && process != null ? process.pid() : 0L);
        m.put("bridgeStartedAt", startedAt);
        m.put("bridgeError", lastError == null ? "" : lastError);

        m.put("bridgeExitCode", lastExitCode == null ? 0 : lastExitCode);
        m.put("bridgeLastExitReason", lastExitReason == null ? "" : lastExitReason);
        m.put("bridgeLastStdout", tail(lastStdout, 800));
        m.put("bridgeLastStderr", tail(lastStderr, 800));

        m.put("backendMode", backendMode);
        m.put("backendLoaded", backendLoaded);
        m.put("backendInitError", backendInitError);
        return m;
    }

    public synchronized boolean isRunning() {
        if (process == null) return false;
        boolean alive = process.isAlive();
        if (!alive) {
            try {
                lastExitCode = process.exitValue();
            } catch (Exception ignored) {}
            if (lastExitReason == null || lastExitReason.isBlank()) lastExitReason = "bridge process exited";
        }
        return alive;
    }

    private void startPumpThreads(Process p) {
        outThread = new Thread(() -> pump(p.getInputStream(), false), "link-bridge-stdout");
        errThread = new Thread(() -> pump(p.getErrorStream(), true), "link-bridge-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();

        Thread watcher = new Thread(() -> {
            try {
                int ec = p.waitFor();
                lastExitCode = ec;
                if (lastExitReason == null || lastExitReason.isBlank()) lastExitReason = "bridge process exited (code=" + ec + ")";
                if (lastError == null || lastError.isBlank()) {
                    if (ec != 0) lastError = "bridge exited with code " + ec;
                }
            } catch (InterruptedException ignored) {}
        }, "link-bridge-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void pump(InputStream in, boolean stderr) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (stderr) {
                    lastStderr = appendLine(lastStderr, line, 4000);
                } else {
                    lastStdout = appendLine(lastStdout, line, 4000);
                }
                parseBackendHints(line);
            }
        } catch (Exception e) {
            if (stderr) {
                lastStderr = appendLine(lastStderr, "[pump-error] " + e.getMessage(), 4000);
            } else {
                lastStdout = appendLine(lastStdout, "[pump-error] " + e.getMessage(), 4000);
            }
        }
    }

    private void parseBackendHints(String line) {
        if (line == null) return;
        String s = line.trim();
        if (s.contains("abletonlink loaded")) {
            backendLoaded = true;
        }
        if (s.contains("backend init success") || s.contains("mode=abletonlink-active")) {
            backendMode = "abletonlink-active";
            backendLoaded = true;
            backendInitError = "";
        }
        if (s.contains("fallback to ack-only")) {
            backendMode = "ack-only";
            backendLoaded = false;
            backendInitError = s;
            lastError = s;
        }
        if (s.contains("init failed") || s.contains("require failed")) {
            backendMode = "abletonlink-failed";
            backendLoaded = false;
            backendInitError = s;
            lastError = s;
        }
    }

    private static String appendLine(String base, String line, int maxChars) {
        String s = (base == null || base.isBlank()) ? line : (base + "\n" + line);
        if (s.length() <= maxChars) return s;
        return s.substring(s.length() - maxChars);
    }

    private static String tail(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(s.length() - maxChars);
    }
}
