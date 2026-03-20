package dbclient.sync.drivers;

import org.deepsymmetry.libcarabiner.Runner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Carabiner 进程管理器
 * 
 * 职责：
 * 1. 确保系统中只有一个 Carabiner 实例
 * 2. 启动前强制杀死所有残留进程
 * 3. 停止时确保进程完全退出
 * 4. 注册 shutdown hook，主程序退出时自动清理
 * 
 * 注意：这是对 lib-carabiner Runner 的包装，添加单实例控制
 */
public class CarabinerProcessManager {
    
    private static final String CARABINER_PROCESS_NAME = "Carabiner";
    private static volatile boolean shutdownHookRegistered = false;
    private static volatile String ourInstancePid = null; // 记录本实例 PID
    
    /**
     * 启动 Carabiner，确保单实例
     * 策略：先优雅停止 runner，再确认退出，最后兜底强杀
     */
    public static synchronized void start(Runner runner, int port, int updateIntervalMs) throws Exception {
        // 1. 先优雅停止 runner（如果之前有实例）
        System.out.println("[CarabinerProcessManager] Step 1: Graceful stop existing instance...");
        try {
            runner.stop();
        } catch (Exception e) {
            System.out.println("[CarabinerProcessManager] Graceful stop failed (may be expected): " + e.getMessage());
        }
        
        // 2. 等待本实例退出（最多 3 秒）
        System.out.println("[CarabinerProcessManager] Step 2: Waiting for our instance to exit...");
        if (ourInstancePid != null) {
            boolean exited = waitForPidExit(ourInstancePid, 3000);
            if (!exited) {
                System.out.println("[CarabinerProcessManager] Warning: Our instance (" + ourInstancePid + ") did not exit gracefully");
            }
            ourInstancePid = null;
        }
        
        // 3. 检查是否还有其他残留实例，兜底强杀
        int remaining = countCarabinerProcesses();
        if (remaining > 0) {
            System.out.println("[CarabinerProcessManager] Step 3: Found " + remaining + " residual instance(s), force killing...");
            killAllCarabinerProcesses();
            waitForCleanup(2000);
        }
        
        // 4. 设置 runner 配置
        runner.setPort(port);
        runner.setUpdateInterval(updateIntervalMs);
        
        // 5. 启动新实例
        System.out.println("[CarabinerProcessManager] Step 4: Starting new instance...");
        runner.start();
        
        // 6. 记录新实例 PID
        ourInstancePid = detectCarabinerPid();
        System.out.println("[CarabinerProcessManager] New instance PID: " + ourInstancePid);
        
        // 7. 注册 shutdown hook（只注册一次）
        registerShutdownHook(runner);
        
        System.out.println("[CarabinerProcessManager] Carabiner started successfully");
    }
    
    /**
     * 等待指定 PID 的进程退出
     * @param pid 进程 ID
     * @param timeoutMs 超时时间
     * @return 是否已退出
     */
    private static boolean waitForPidExit(String pid, int timeoutMs) {
        if (pid == null || pid.equals("unknown")) return true;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                // 检查进程是否存在
                Process check = Runtime.getRuntime().exec(new String[]{"kill", "-0", pid});
                int exitCode = check.waitFor();
                if (exitCode != 0) {
                    return true; // 进程已不存在
                }
            } catch (Exception e) {
                return true; // 检查失败，假设已退出
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
    
    /**
     * 停止 Carabiner，确保完全退出
     * 策略：先优雅停止 runner，再等待本实例退出，最后兜底强杀
     */
    public static synchronized void stop(Runner runner) {
        System.out.println("[CarabinerProcessManager] Stopping Carabiner...");
        
        // 1. 记录要停止的 PID
        String pidToStop = ourInstancePid;
        ourInstancePid = null; // 清空记录
        
        // 2. 先优雅停止 runner
        System.out.println("[CarabinerProcessManager] Step 1: Graceful stop...");
        try {
            runner.stop();
        } catch (Exception e) {
            System.err.println("[CarabinerProcessManager] Graceful stop error: " + e.getMessage());
        }
        
        // 3. 等待本实例退出（最多 2 秒）
        if (pidToStop != null) {
            System.out.println("[CarabinerProcessManager] Step 2: Waiting for PID " + pidToStop + " to exit...");
            boolean exited = waitForPidExit(pidToStop, 2000);
            if (exited) {
                System.out.println("[CarabinerProcessManager] Instance exited gracefully");
                return;
            }
            System.out.println("[CarabinerProcessManager] Warning: Instance did not exit gracefully, forcing kill...");
        }
        
        // 4. 兜底：强制杀死所有 Carabiner 进程
        int remaining = countCarabinerProcesses();
        if (remaining > 0) {
            System.out.println("[CarabinerProcessManager] Step 3: Force killing " + remaining + " instance(s)...");
            killAllCarabinerProcesses();
            waitForCleanup(2000);
        }
        
        System.out.println("[CarabinerProcessManager] Carabiner stopped");
    }
    
    /**
     * 强制杀死所有 Carabiner 进程
     */
    private static void killAllCarabinerProcesses() {
        System.out.println("[CarabinerProcessManager] Killing all Carabiner processes...");
        
        try {
            // 使用 pkill 杀死所有 Carabiner 进程
            Process pkill = Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", CARABINER_PROCESS_NAME});
            pkill.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[CarabinerProcessManager] pkill failed: " + e.getMessage());
        }
        
        // 验证清理结果
        int remaining = countCarabinerProcesses();
        if (remaining > 0) {
            System.err.println("[CarabinerProcessManager] Warning: " + remaining + " Carabiner processes still running after pkill");
        } else {
            System.out.println("[CarabinerProcessManager] All Carabiner processes killed");
        }
    }
    
    /**
     * 统计当前 Carabiner 进程数
     */
    private static int countCarabinerProcesses() {
        try {
            Process ps = Runtime.getRuntime().exec(new String[]{"pgrep", "-c", "-f", CARABINER_PROCESS_NAME});
            BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String line = reader.readLine();
            ps.waitFor(1, TimeUnit.SECONDS);
            if (line != null) {
                return Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            // pgrep 返回非零退出码表示没有找到进程
        }
        return 0;
    }
    
    /**
     * 检测 Carabiner PID
     */
    private static String detectCarabinerPid() {
        try {
            Process ps = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", CARABINER_PROCESS_NAME});
            BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String line = reader.readLine();
            ps.waitFor(1, TimeUnit.SECONDS);
            if (line != null) {
                return line.trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }
    
    /**
     * 等待清理完成
     */
    private static void waitForCleanup(int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (countCarabinerProcesses() == 0) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
    
    /**
     * 注册 shutdown hook
     */
    private static void registerShutdownHook(Runner runner) {
        if (shutdownHookRegistered) {
            return;
        }
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[CarabinerProcessManager] Shutdown hook triggered, cleaning up...");
            stop(runner);
        }, "carabiner-cleanup"));
        
        shutdownHookRegistered = true;
        System.out.println("[CarabinerProcessManager] Shutdown hook registered");
    }
    
    /**
     * 获取当前 Carabiner 进程数（供外部监控）
     */
    public static int getInstanceCount() {
        return countCarabinerProcesses();
    }

    /**
     * 获取本实例 PID（供外部监控）
     */
    public static String getOurInstancePid() {
        return ourInstancePid;
    }
}