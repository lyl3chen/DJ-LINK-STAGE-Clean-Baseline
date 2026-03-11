package dbclient.sync.drivers;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ableton Link bridge 子进程控制器（最小版）。
 */
public class AbletonLinkBridgeProcess {
    private volatile Process process;
    private volatile long startedAt = 0L;
    private volatile String lastError = "";

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

            File logsDir = new File(workDir, "../logs");
            if (!logsDir.exists()) logsDir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder("node", script.getAbsolutePath());
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logsDir, "ableton-link-bridge.log")));

            process = pb.start();
            startedAt = System.currentTimeMillis();
            lastError = "";

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
        return m;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }
}
