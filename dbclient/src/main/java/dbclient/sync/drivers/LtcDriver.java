package dbclient.sync.drivers;

import dbclient.sync.OutputDriver;
import dbclient.sync.TimecodeClock;

import javax.sound.sampled.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class LtcDriver implements OutputDriver {
    // 这一段是真实 LTC 音频输出驱动：根据主时钟持续生成可被外部设备识别的时间码音频信号。
    private volatile boolean running;
    private volatile double seconds;
    private volatile int frameInSecond;
    private volatile double signalLevel = 0.0;
    private volatile String activeDevice = "-";
    private volatile String lastError = "";
    private volatile String outputState = "IDLE";
    // Phase A: 可解释性状态（不参与选路决策）
    private volatile String configuredTarget = "default";
    private volatile String matchedDevice = "-";
    private volatile String matchMode = "fallback";
    private volatile int matchScore = 0;
    private volatile String endpointType = "unknown";
    private volatile String channelRole = "unknown";
    private volatile boolean deviceOpenable = false;
    private volatile String warning = "";
    private volatile String lastSuccessfulDevice = "-";
    private volatile boolean sourcePlaying = false;
    private volatile boolean sourceActive = false;
    private volatile String sourceState = "OFFLINE";
    private volatile long lastSourceUpdateMs = 0L;
    private volatile TimecodeClock clock;

    // === 三层时基架构 ===
    // 1) 上游 position reference (仅用于初始对齐和大跳变检测)
    private volatile long upstreamPositionMs = 0L;
    private volatile boolean upstreamPositionValid = false;

    // 2) 上游 playback rate reference (用于决定 LTC 前进速度)
    private volatile double upstreamRate = 1.0;
    private volatile double smoothedRate = 1.0;
    private static final double RATE_SMOOTH_FACTOR = 0.02; // 慢速平滑

    // 3) 本地稳定 LTC clock (连续输出驱动)
    private volatile boolean blockClockInit = false;
    private volatile double nextBlockStartSec = 0.0;
    // 本地 LTC 位置（sample 计数）
    private volatile long localLtcSamplePosition = 0L;
    private volatile long localLtcFramePosition = 0L; // 当前帧号

    // === 媒体锚点管理 ===
    // 当前媒体上下文标识
    private volatile String currentTrackId = "";
    private volatile String currentRekordboxId = "";
    private volatile int currentPlayerId = 0;
    private volatile long lastMediaChangeDetectedMs = 0L;
    private static final long MEDIA_CHANGE_DEBOUNCE_MS = 1000; // 媒体变化防抖

    // 上一次有效的锚点位置（用于检测跳变）
    private volatile long lastAnchoredPositionMs = 0L;

    // === Reanchor 执行状态（真正执行，不只是标记） ===
    // 检测到的 reanchor 触发条件
    private volatile boolean pendingReanchor = false;
    private volatile String reanchorReason = "";
    private volatile long reanchorUpstreamPositionMs = 0L;
    private volatile long reanchorRequestedAtMs = 0L;
    // 实际执行的 reanchor
    private volatile long lastReanchorAppliedAtMs = 0L;
    private volatile long reanchorCount = 0L;
    // 调试：上一次 reanchor 的详情
    private volatile String lastReanchorReason = "";
    private volatile long lastReanchorUpstreamPositionMs = 0L;

    // === Reanchor 判定原始输入（完全可观测） ===
    // 当前帧数据
    private volatile String currTrackId = "";
    private volatile String currRekordboxId = "";
    private volatile int currPlayerId = 0;
    private volatile long currUpstreamPositionMs = 0L;
    private volatile String upstreamPositionSource = "none"; // currentTimeMs / beatTimeMs / fallback / none
    // 上一帧数据
    private volatile String prevTrackId = "";
    private volatile String prevRekordboxId = "";
    private volatile int prevPlayerId = 0;
    private volatile long prevUpstreamPositionMs = 0L;
    // 判定结果
    private volatile boolean mediaContextChanged = false;
    private volatile boolean nearZeroDetected = false;
    private volatile boolean bigJumpDetected = false;
    private volatile long computedJumpDiffMs = 0L;
    // 判定执行状态
    private volatile boolean reanchorCheckRan = false;
    private volatile boolean reanchorTriggerMatched = false;
    private volatile String reanchorTriggerType = "";
    private volatile String reanchorSuppressedReason = "";

    // === 四条路径状态机 ===
    // 路径A: 正常连续推进 (rate following)
    // 路径B: 重锚 (media change / big jump)
    // 路径C: Pause-Hold (停止/暂停)
    // 路径D: Resume (暂停后恢复)

    // 当前控制模式
    private volatile String rateControlMode = "IDLE"; // IDLE / NORMAL / REANCHOR / PAUSE / RESUME
    private volatile String lastAnchorMode = "";

    // Pause/Resume 状态
    private volatile boolean wasPlaying = false;
    private volatile boolean pauseTransitionDetected = false;
    private volatile boolean resumeTransitionDetected = false;
    private volatile long lastPauseAtMs = 0L;
    private volatile long lastResumeAtMs = 0L;

    // Loop 状态检测
    private volatile boolean loopLikeJumpDetected = false;
    private volatile long lastLoopJumpAtMs = 0L;
    private volatile long lastLoopJumpFromMs = 0L;
    private volatile long lastLoopJumpToMs = 0L;

    // 媒体变化时暂停检测
    private volatile boolean mediaChangedWhilePaused = false;

    // Soft sync / Hard reanchor
    private volatile long lastSoftSyncAtMs = 0L;
    private volatile long lastHardReanchorAtMs = 0L;
    private volatile String lastHoldReason = "";
    private volatile boolean lastResumeReanchorApplied = false;

    // 位置误差
    private volatile long positionErrorMs = 0L;

    // 目标速率 vs 实际应用速率
    private volatile double targetRate = 1.0;
    private volatile double appliedRate = 1.0;

    // === Update() 原始输入观测 ===
    private volatile long updateInvocationCount = 0L;
    private volatile long lastUpdateAtMs = 0L;
    private volatile String lastUpdateKeys = ""; // 逗号分隔的 key 列表
    // 原始值
    private volatile String rawCurrentTimeMs = "";
    private volatile String rawBeatTimeMs = "";
    private volatile String rawRemainingTimeMs = "";
    private volatile String rawPlayerId = "";
    private volatile String rawTrackId = "";
    private volatile String rawRekordboxId = "";
    private volatile String rawPlaying = "";
    private volatile String rawBpm = "";
    private volatile String rawPitch = "";
    // 解析后值
    private volatile long parsedCurrentTimeMs = 0L;
    private volatile long parsedBeatTimeMs = 0L;
    private volatile int parsedPlayerId = 0;
    private volatile String parsedTrackId = "";
    private volatile String parsedRekordboxId = "";
    private volatile boolean parsedPlaying = false;
    private volatile double parsedBpm = 0.0;
    private volatile double parsedPitch = 0.0;

    // 状态判断阈值
    private static final double BIG_JUMP_THRESHOLD_SEC = 0.5; // 500ms 大跳变
    private static final double SMALL_ERROR_THRESHOLD_SEC = 0.05; // 50ms 小误差
    private static final double BLOCK_RELOCK_THRESHOLD_SEC = 0.020; // 20ms
    private static final double POSITION_NEAR_ZERO_SEC = 0.5; // 接近 0 的阈值
    private Map<String, Object> cfg = new LinkedHashMap<>();

    private SourceDataLine line;
    private Thread audioThread;

    public String name() { return "ltc"; }

    public synchronized void start(Map<String, Object> config) {
        cfg = config != null ? new LinkedHashMap<>(config) : new LinkedHashMap<>();
        int sampleRate = intCfg("sampleRate", 48000);
        String deviceName = strCfg("deviceName", "default");
        configuredTarget = deviceName;

        try {
            AudioFormat fmt = chooseAndOpenLine(sampleRate, deviceName);
            activeDevice = describeActiveDevice(line, deviceName);
            matchedDevice = activeDevice;
            line.start();
            running = true;
            outputState = "RUNNING";
            blockClockInit = false;
            nextBlockStartSec = 0.0;
            lastError = "";
            endpointType = classifyEndpoint(activeDevice);
            channelRole = classifyChannelRole(activeDevice);
            deviceOpenable = true;
            matchMode = matchModeOf(configuredTarget, activeDevice);
            matchScore = computeMatchScore(configuredTarget, activeDevice, true);
            warning = buildWarning(endpointType, configuredTarget, activeDevice, "");
            lastSuccessfulDevice = activeDevice;
            System.out.println("[LTC] started device=" + activeDevice + " fps=" + intCfg("fps",25) + " sampleRate=" + (int)fmt.getSampleRate() + " gainDb=" + numCfg("gainDb",-8.0));
            audioThread = new Thread(() -> pumpAudio(fmt), "ltc-audio-thread");
            audioThread.setDaemon(true);
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
        } catch (Exception e) {
            running = false;
            outputState = "ERROR";
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            String occupancy = detectOccupancyHint(strCfg("deviceName", "default"));
            lastError = occupancy.isBlank() ? msg : (msg + " | " + occupancy);
            activeDevice = "-";
            matchedDevice = "-";
            endpointType = classifyEndpoint(configuredTarget);
            channelRole = classifyChannelRole(configuredTarget);
            deviceOpenable = false;
            matchMode = "fallback";
            matchScore = computeMatchScore(configuredTarget, "-", false);
            warning = buildWarning(endpointType, configuredTarget, "-", lastError);
            System.out.println("[LTC] start failed: " + lastError);
        }
    }

    public synchronized void stop() {
        running = false;
        outputState = "STOPPED";
        blockClockInit = false;
        signalLevel = 0.0;
        if (audioThread != null) {
            try { audioThread.join(300); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {}
            line = null;
        }
    }

    public void update(Map<String, Object> state) {
        if (!running || state == null) return;

        // === 记录原始 state keys ===
        updateInvocationCount++;
        lastUpdateAtMs = System.currentTimeMillis();
        StringBuilder keysSb = new StringBuilder();
        for (String k : state.keySet()) {
            if (keysSb.length() > 0) keysSb.append(",");
            keysSb.append(k);
        }
        lastUpdateKeys = keysSb.toString();

        // 记录原始值
        rawCurrentTimeMs = String.valueOf(state.get("currentTimeMs"));
        rawBeatTimeMs = String.valueOf(state.get("beatTimeMs"));
        rawRemainingTimeMs = String.valueOf(state.get("remainingTimeMs"));
        rawPlayerId = String.valueOf(state.get("playerId"));
        rawTrackId = String.valueOf(state.get("trackId"));
        rawRekordboxId = String.valueOf(state.get("rekordboxId"));
        rawPlaying = String.valueOf(state.get("playing"));
        rawBpm = String.valueOf(state.get("bpm"));
        rawPitch = String.valueOf(state.get("pitch"));

        Object t = state.get("masterTimeSec");
        Object sp = state.get("sourcePlaying");
        Object sa = state.get("sourceActive");
        sourcePlaying = Boolean.TRUE.equals(sp);
        sourceActive = Boolean.TRUE.equals(sa);
        sourceState = String.valueOf(state.getOrDefault("sourceState", "OFFLINE"));
        lastSourceUpdateMs = System.currentTimeMillis();

        // === Reanchor 判定：记录原始输入 ===
        // 先保存上一帧数据
        prevTrackId = currTrackId;
        prevRekordboxId = currRekordboxId;
        prevPlayerId = currPlayerId;
        prevUpstreamPositionMs = currUpstreamPositionMs;

        // === 1) 捕获上游 position reference ===
        Object ctMs = state.get("currentTimeMs");
        Object btMs = state.get("beatTimeMs");
        long newPositionMs = 0L;
        upstreamPositionSource = "none";

        if (ctMs instanceof Number) {
            newPositionMs = ((Number) ctMs).longValue();
            upstreamPositionValid = true;
            upstreamPositionSource = "currentTimeMs";
            parsedCurrentTimeMs = newPositionMs;
        } else if (btMs instanceof Number) {
            // 回退到 beatTimeMs
            newPositionMs = ((Number) btMs).longValue();
            upstreamPositionValid = true;
            upstreamPositionSource = "beatTimeMs";
            parsedBeatTimeMs = newPositionMs;
        } else {
            upstreamPositionValid = false;
            upstreamPositionSource = "none";
            parsedCurrentTimeMs = 0L;
            parsedBeatTimeMs = 0L;
        }

        // 解析其他字段
        parsedPlayerId = intVal(state, "playerId", 0);
        parsedTrackId = strVal(state, "trackId", "");
        parsedRekordboxId = strVal(state, "rekordboxId", "");
        parsedPlaying = Boolean.TRUE.equals(state.get("playing"));
        parsedBpm = numVal(state, "bpm", 0.0);
        parsedPitch = numVal(state, "pitch", 0.0);

        currUpstreamPositionMs = newPositionMs;

        // === 媒体锚点管理：捕获媒体上下文 ===
        String newTrackId = strVal(state, "trackId", "");
        String newRekordboxId = strVal(state, "rekordboxId", "");
        int newPlayerId = intVal(state, "playerId", 0);

        currTrackId = newTrackId;
        currRekordboxId = newRekordboxId;
        currPlayerId = newPlayerId;

        // 重置判定状态
        reanchorCheckRan = true;
        reanchorTriggerMatched = false;
        reanchorTriggerType = "";
        reanchorSuppressedReason = "";

        // 检测媒体上下文变化
        mediaContextChanged = !newTrackId.equals(prevTrackId)
            || !newRekordboxId.equals(prevRekordboxId)
            || newPlayerId != prevPlayerId;

        if (mediaContextChanged) {
            long now = System.currentTimeMillis();
            if (now - lastMediaChangeDetectedMs > MEDIA_CHANGE_DEBOUNCE_MS) {
                lastMediaChangeDetectedMs = now;
                currentTrackId = newTrackId;
                currentRekordboxId = newRekordboxId;
                currentPlayerId = newPlayerId;
                // 立即触发 reanchor
                pendingReanchor = true;
                reanchorReason = "media_context_change";
                reanchorUpstreamPositionMs = newPositionMs;
                reanchorRequestedAtMs = now;
                reanchorTriggerMatched = true;
                reanchorTriggerType = "media_context_change";
                System.out.println("[LTC] MEDIA REANCHOR TRIGGERED: trackId=" + newTrackId + " rekordboxId=" + newRekordboxId + " playerId=" + newPlayerId + " pos=" + newPositionMs);
            } else {
                reanchorSuppressedReason = "media_change_debounce";
            }
        }

        // 检测位置跳变（可能是 seek / cue / loop jump）
        if (upstreamPositionValid) {
            computedJumpDiffMs = Math.abs(newPositionMs - lastAnchoredPositionMs);
            nearZeroDetected = newPositionMs < 500 && lastAnchoredPositionMs > 5000;
            bigJumpDetected = computedJumpDiffMs > (long)(BIG_JUMP_THRESHOLD_SEC * 1000);

            if (nearZeroDetected && !pendingReanchor) {
                // 位置跳到接近 0，触发 reanchor
                pendingReanchor = true;
                reanchorReason = "position_jump_to_zero";
                reanchorUpstreamPositionMs = newPositionMs;
                reanchorRequestedAtMs = System.currentTimeMillis();
                reanchorTriggerMatched = true;
                reanchorTriggerType = "position_jump_to_zero";
                System.out.println("[LTC] POSITION JUMP REANCHOR TRIGGERED: from=" + lastAnchoredPositionMs + " to=" + newPositionMs);
            } else if (bigJumpDetected && !pendingReanchor) {
                // 大跳变触发 reanchor
                pendingReanchor = true;
                reanchorReason = "big_jump";
                reanchorUpstreamPositionMs = newPositionMs;
                reanchorRequestedAtMs = System.currentTimeMillis();
                reanchorTriggerMatched = true;
                reanchorTriggerType = "big_jump";
                System.out.println("[LTC] BIG JUMP REANCHOR TRIGGERED: from=" + lastAnchoredPositionMs + " to=" + newPositionMs);
            }
        } else {
            nearZeroDetected = false;
            bigJumpDetected = false;
            computedJumpDiffMs = 0;
            reanchorSuppressedReason = upstreamPositionSource.equals("none") ? "no_upstream_position" : reanchorSuppressedReason;
        }

        if (newPositionMs > 0) {
            lastAnchoredPositionMs = newPositionMs;
        }
        upstreamPositionMs = newPositionMs;

        // === Pause/Resume 转换检测 ===
        long now = System.currentTimeMillis();
        pauseTransitionDetected = false;
        resumeTransitionDetected = false;

        if (!parsedPlaying && wasPlaying) {
            // Playing -> Not Playing (Pause)
            pauseTransitionDetected = true;
            lastPauseAtMs = now;
            rateControlMode = "PAUSE";
            lastHoldReason = "pause";
            System.out.println("[LTC] PAUSE DETECTED at " + newPositionMs + "ms");
        } else if (parsedPlaying && !wasPlaying) {
            // Not Playing -> Playing (Resume)
            resumeTransitionDetected = true;
            lastResumeAtMs = now;
            rateControlMode = "RESUME";
            System.out.println("[LTC] RESUME DETECTED at " + newPositionMs + "ms");
        }

        // 检测媒体变化时是否处于暂停状态
        if (mediaContextChanged && !parsedPlaying) {
            mediaChangedWhilePaused = true;
            System.out.println("[LTC] MEDIA CHANGED WHILE PAUSED: trackId=" + newTrackId);
        }

        // Loop 跳变检测：位置回到之前的位置（loop repeat）
        if (upstreamPositionValid && parsedPlaying) {
            // 检测是否在 loop 区间内反复跳回
            if (lastLoopJumpToMs > 0 && newPositionMs == lastLoopJumpToMs && prevUpstreamPositionMs > newPositionMs) {
                // 位置又跳回之前的 loop 点
                loopLikeJumpDetected = true;
                lastLoopJumpAtMs = now;
                lastLoopJumpFromMs = prevUpstreamPositionMs;
                lastLoopJumpToMs = newPositionMs;
                System.out.println("[LTC] LOOP-LIKE JUMP DETECTED: from=" + prevUpstreamPositionMs + " to=" + newPositionMs);
            } else if (Math.abs(newPositionMs - prevUpstreamPositionMs) > 2000 && newPositionMs < prevUpstreamPositionMs) {
                // 大幅回跳，可能是 loop 开始
                lastLoopJumpFromMs = prevUpstreamPositionMs;
                lastLoopJumpToMs = newPositionMs;
            }
        }

        // 更新播放状态
        wasPlaying = parsedPlaying;

        // === 2) 捕获上游 playback rate reference ===
        // 优先用 effectiveBpm / baseBpm 计算，其次用 pitch
        Double effectiveBpm = null;
        Double baseBpm = null;
        Double pitch = null;

        Object eb = state.get("effectiveBpm");
        Object bb = state.get("bpm");
        Object p = state.get("pitch");

        if (eb instanceof Number) effectiveBpm = ((Number) eb).doubleValue();
        if (bb instanceof Number) baseBpm = ((Number) bb).doubleValue();
        if (p instanceof Number) pitch = ((Number) p).doubleValue();

        double newRate = 1.0;
        if (effectiveBpm != null && baseBpm != null && baseBpm > 0) {
            newRate = effectiveBpm / baseBpm;
        } else if (pitch != null) {
            newRate = 1.0 + pitch / 100.0;
        }
        // 平滑 rate 变化
        smoothedRate = smoothedRate + (newRate - smoothedRate) * RATE_SMOOTH_FACTOR;
        upstreamRate = newRate;

        if (t instanceof Number) {
            seconds = ((Number) t).doubleValue();
        }
        Object clk = state.get("__clock");
        if (clk instanceof TimecodeClock) clock = (TimecodeClock) clk;
    }

    private static String strVal(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    private static double numVal(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    public Map<String, Object> status() {
        FrameRateMode mode = FrameRateMode.fromConfig(cfg.get("fps"));
        int fps = mode.nominalFps;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("seconds", seconds);
        m.put("fps", fps);
        m.put("frameRate", mode.rateFps);
        m.put("dropFrame", mode.dropFrame);
        m.put("sampleRate", line != null ? (int) line.getFormat().getSampleRate() : intCfg("sampleRate", 48000));
        m.put("deviceName", strCfg("deviceName", "default"));
        m.put("activeDevice", activeDevice);
        m.put("gainDb", numCfg("gainDb", -8.0));
        m.put("frame", frameInSecond);
        m.put("signalLevel", signalLevel);
        m.put("timecode", toTimecode(seconds, fps));
        m.put("outputState", outputState);
        m.put("sourcePlaying", sourcePlaying);
        m.put("sourceActive", sourceActive);
        m.put("sourceState", sourceState);

        // Phase A 解释性状态
        m.put("configuredTarget", configuredTarget);
        m.put("matchedDevice", matchedDevice);
        m.put("matchMode", matchMode);
        m.put("matchScore", matchScore);
        m.put("endpointType", endpointType);
        m.put("channelRole", channelRole);
        m.put("deviceOpenable", deviceOpenable);
        m.put("warning", warning);
        m.put("lastSuccessfulDevice", lastSuccessfulDevice);

        // 三层时基架构状态
        m.put("upstreamPositionMs", upstreamPositionMs);
        m.put("upstreamPositionValid", upstreamPositionValid);
        m.put("upstreamRate", upstreamRate);
        m.put("smoothedRate", smoothedRate);
        m.put("localLtcSamplePosition", localLtcSamplePosition);
        m.put("localLtcFramePosition", localLtcFramePosition);

        // 媒体锚点管理状态
        m.put("currentTrackId", currentTrackId);
        m.put("currentRekordboxId", currentRekordboxId);
        m.put("currentPlayerId", currentPlayerId);
        m.put("lastMediaChangeDetectedMs", lastMediaChangeDetectedMs);

        // Reanchor 执行状态
        m.put("pendingReanchor", pendingReanchor);
        m.put("reanchorReason", reanchorReason);
        m.put("reanchorRequestedAtMs", reanchorRequestedAtMs);
        m.put("reanchorUpstreamPositionMs", reanchorUpstreamPositionMs);
        m.put("lastReanchorAppliedAtMs", lastReanchorAppliedAtMs);
        m.put("reanchorCount", reanchorCount);
        m.put("lastReanchorReason", lastReanchorReason);
        m.put("lastReanchorUpstreamPositionMs", lastReanchorUpstreamPositionMs);

        // Reanchor 判定原始输入（完全可观测）
        m.put("currTrackId", currTrackId);
        m.put("currRekordboxId", currRekordboxId);
        m.put("currPlayerId", currPlayerId);
        m.put("currUpstreamPositionMs", currUpstreamPositionMs);
        m.put("upstreamPositionSource", upstreamPositionSource);
        m.put("prevTrackId", prevTrackId);
        m.put("prevRekordboxId", prevRekordboxId);
        m.put("prevPlayerId", prevPlayerId);
        m.put("prevUpstreamPositionMs", prevUpstreamPositionMs);
        m.put("mediaContextChanged", mediaContextChanged);
        m.put("nearZeroDetected", nearZeroDetected);
        m.put("bigJumpDetected", bigJumpDetected);
        m.put("computedJumpDiffMs", computedJumpDiffMs);
        m.put("reanchorCheckRan", reanchorCheckRan);
        m.put("reanchorTriggerMatched", reanchorTriggerMatched);
        m.put("reanchorTriggerType", reanchorTriggerType);
        m.put("reanchorSuppressedReason", reanchorSuppressedReason);

        // Update() 原始输入观测
        m.put("updateInvocationCount", updateInvocationCount);
        m.put("lastUpdateAtMs", lastUpdateAtMs);
        m.put("lastUpdateKeys", lastUpdateKeys);
        m.put("rawCurrentTimeMs", rawCurrentTimeMs);
        m.put("rawBeatTimeMs", rawBeatTimeMs);
        m.put("rawRemainingTimeMs", rawRemainingTimeMs);
        m.put("rawPlayerId", rawPlayerId);
        m.put("rawTrackId", rawTrackId);
        m.put("rawRekordboxId", rawRekordboxId);
        m.put("rawPlaying", rawPlaying);
        m.put("rawBpm", rawBpm);
        m.put("rawPitch", rawPitch);
        m.put("parsedCurrentTimeMs", parsedCurrentTimeMs);
        m.put("parsedBeatTimeMs", parsedBeatTimeMs);
        m.put("parsedPlayerId", parsedPlayerId);
        m.put("parsedTrackId", parsedTrackId);
        m.put("parsedRekordboxId", parsedRekordboxId);
        m.put("parsedPlaying", parsedPlaying);
        m.put("parsedBpm", parsedBpm);
        m.put("parsedPitch", parsedPitch);

        // 四条路径状态机
        m.put("rateControlMode", rateControlMode);
        m.put("lastAnchorMode", lastAnchorMode);
        m.put("wasPlaying", wasPlaying);
        m.put("pauseTransitionDetected", pauseTransitionDetected);
        m.put("resumeTransitionDetected", resumeTransitionDetected);
        m.put("lastPauseAtMs", lastPauseAtMs);
        m.put("lastResumeAtMs", lastResumeAtMs);
        m.put("loopLikeJumpDetected", loopLikeJumpDetected);
        m.put("lastLoopJumpAtMs", lastLoopJumpAtMs);
        m.put("mediaChangedWhilePaused", mediaChangedWhilePaused);
        m.put("lastSoftSyncAtMs", lastSoftSyncAtMs);
        m.put("lastHardReanchorAtMs", lastHardReanchorAtMs);
        m.put("lastHoldReason", lastHoldReason);
        m.put("lastResumeReanchorApplied", lastResumeReanchorApplied);
        m.put("positionErrorMs", positionErrorMs);
        m.put("targetRate", targetRate);
        m.put("appliedRate", appliedRate);

        m.put("blockRelockThresholdSec", BLOCK_RELOCK_THRESHOLD_SEC);
        m.put("bigJumpThresholdSec", BIG_JUMP_THRESHOLD_SEC);
        m.put("smallErrorThresholdSec", SMALL_ERROR_THRESHOLD_SEC);
        m.put("error", lastError);
        return m;
    }

    private void pumpAudio(AudioFormat fmt) {
        final int sampleRate = (int) fmt.getSampleRate();
        final FrameRateMode mode = FrameRateMode.fromConfig(cfg.get("fps"));
        final double gainDb = numCfg("gainDb", -8.0);
        final double amp = Math.max(0.01, Math.min(0.95, Math.pow(10.0, gainDb / 20.0)));

        final int bufferSamples = sampleRate / 40; // ~25ms
        final byte[] out = new byte[bufferSamples * 2];
        final double bitRate = 80.0 * mode.rateFps;

        final LtcFrameBuilder frameBuilder = new LtcFrameBuilder(mode);
        final LtcBmcModulator mod = new LtcBmcModulator(sampleRate, bitRate);

        // 本地 LTC 时基参数
        final double samplesPerFrameExact = (double) sampleRate / mode.rateFps;
        final long bigJumpThresholdSamples = (long) (BIG_JUMP_THRESHOLD_SEC * sampleRate);
        final long smallErrorThresholdSamples = (long) (SMALL_ERROR_THRESHOLD_SEC * sampleRate);

        // 本地 LTC 位置（sample 计数）
        long localLtcSamplePos = 0L;
        long localLtcFramePos = 0L;
        long nextFrameBoundarySample = 0L;
        boolean framePrimed = false;
        boolean initialAligned = false;

        while (running && line != null && line.isOpen()) {
            outputState = "OUTPUTTING";

            // === 判断当前状态 ===
            boolean isPlaying = sourcePlaying && sourceActive;

            if (!blockClockInit) {
                // 首次启动
                nextBlockStartSec = 0.0;
                localLtcSamplePos = 0L;
                localLtcFramePos = 0L;
                nextFrameBoundarySample = (long) samplesPerFrameExact;
                framePrimed = false;
                initialAligned = false;
                blockClockInit = true;
                rateControlMode = "IDLE";
            }

            // === 路径C: Pause-Hold ===
            if (!isPlaying) {
                // LTC 稳定停住
                outputState = "PAUSED";
                rateControlMode = "PAUSE";
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                continue;
            }

            // === 路径D: Resume - 恢复播放时做一次 soft sync ===
            if (resumeTransitionDetected && upstreamPositionValid) {
                // Resume 时不做硬重锚，而是做一次软贴合
                long targetSamplePos = (upstreamPositionMs * sampleRate) / 1000L;
                // 计算位置误差
                positionErrorMs = (localLtcSamplePos * 1000L) / sampleRate - upstreamPositionMs;
                // 只在误差超过 100ms 时才做一次软贴合
                if (Math.abs(positionErrorMs) > 100) {
                    localLtcSamplePos = targetSamplePos;
                    localLtcFramePos = (long) (localLtcSamplePos / samplesPerFrameExact);
                    nextFrameBoundarySample = (long) ((localLtcFramePos + 1) * samplesPerFrameExact);
                    framePrimed = false;
                    lastSoftSyncAtMs = System.currentTimeMillis();
                    System.out.println("[LTC] RESUME SOFT SYNC: error=" + positionErrorMs + "ms -> aligned to " + upstreamPositionMs + "ms");
                }
                lastResumeReanchorApplied = Math.abs(positionErrorMs) > 100;
                resumeTransitionDetected = false;
                rateControlMode = "NORMAL";
            }

            // === 路径B: Reanchor (媒体变化/大跳变/loop回跳) ===
            if (pendingReanchor && upstreamPositionValid) {
                // 真正执行 reanchor：重置本地 LTC 主位置到 upstream 位置
                long targetSamplePos = (upstreamPositionMs * sampleRate) / 1000L;
                localLtcSamplePos = targetSamplePos;
                localLtcFramePos = (long) (localLtcSamplePos / samplesPerFrameExact);
                nextFrameBoundarySample = (long) ((localLtcFramePos + 1) * samplesPerFrameExact);
                initialAligned = true;
                framePrimed = false;

                // 记录执行详情
                lastReanchorAppliedAtMs = System.currentTimeMillis();
                lastHardReanchorAtMs = System.currentTimeMillis();
                reanchorCount++;
                lastReanchorReason = reanchorReason;
                lastReanchorUpstreamPositionMs = reanchorUpstreamPositionMs;
                lastAnchorMode = "HARD";

                System.out.println("[LTC] REANCHOR APPLIED: reason=" + reanchorReason + " upstreamPos=" + reanchorUpstreamPositionMs + "ms -> localSample=" + localLtcSamplePos + " frame=" + localLtcFramePos);

                // 清除 pending 状态
                pendingReanchor = false;
                reanchorReason = "";
                reanchorUpstreamPositionMs = 0L;
                rateControlMode = "NORMAL";
            } else if (!initialAligned && upstreamPositionValid) {
                // 首次对齐：用上游位置初始化本地 LTC
                long targetSamplePos = (upstreamPositionMs * sampleRate) / 1000L;
                localLtcSamplePos = targetSamplePos;
                localLtcFramePos = (long) (localLtcSamplePos / samplesPerFrameExact);
                nextFrameBoundarySample = (long) ((localLtcFramePos + 1) * samplesPerFrameExact);
                initialAligned = true;
                framePrimed = false;
                rateControlMode = "NORMAL";
                System.out.println("[LTC] INITIAL ALIGN: upstreamPos=" + upstreamPositionMs + "ms -> localSample=" + localLtcSamplePos);
            }

            // === 路径A: 正常连续播放 - 只按 smoothedRate 推进，不做位置纠偏 ===
            // 设置目标速率和应用速率
            targetRate = smoothedRate;
            appliedRate = smoothedRate;
            rateControlMode = "NORMAL";

            // === 路径A: 正常连续播放 - 按 appliedRate 推进 ===
            double effectiveSamplesPerFrame = samplesPerFrameExact / appliedRate;

            double blockStartSec = nextBlockStartSec;
            seconds = blockStartSec;
            double sumSq = 0.0;

            for (int i = 0; i < bufferSamples; i++) {
                long sampleIdx = localLtcSamplePos + i;

                // 检查是否到达帧边界
                if (!framePrimed || sampleIdx >= nextFrameBoundarySample) {
                    // 进入新帧
                    while (sampleIdx >= nextFrameBoundarySample) {
                        localLtcFramePos++;
                        nextFrameBoundarySample = (long) ((localLtcFramePos + 1) * effectiveSamplesPerFrame);
                    }
                    Timecode tc = mode.timecodeFromSeconds(localLtcFramePos / mode.rateFps);
                    frameInSecond = tc.frame;
                    mod.loadFrame(frameBuilder.buildBits(tc));
                    framePrimed = true;
                }

                double sample = mod.nextSample() * amp;
                sumSq += sample * sample;
                short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (sample * 32767.0)));
                out[i * 2] = (byte) (v & 0xff);
                out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }

            // 本地 LTC 位置按 appliedRate 推进
            localLtcSamplePos += (long) (bufferSamples * appliedRate);
            // 同步本地帧位置
            localLtcFramePos = (long) (localLtcSamplePos / effectiveSamplesPerFrame);
            nextBlockStartSec += bufferSamples / (double) sampleRate;

            double rms = Math.sqrt(sumSq / Math.max(1, bufferSamples));
            signalLevel = signalLevel * 0.55 + rms * 0.45;
            try {
                line.write(out, 0, out.length);
            } catch (Exception e) {
                lastError = "音频设备写入失败: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                outputState = "ERROR";
                running = false;
            }
        }

        if (running && (line == null || !line.isOpen())) {
            lastError = "音频输出设备不可用或已断开";
            outputState = "ERROR";
            running = false;
        }
    }

    private AudioFormat chooseAndOpenLine(int requestedSampleRate, String preferredName) throws LineUnavailableException {
        int[] rates = new int[] { requestedSampleRate, 48000, 44100 };
        for (int r : rates) {
            try {
                AudioFormat fmt = new AudioFormat(r, 16, 1, true, false);
                SourceDataLine l = openLineWithFormat(fmt, preferredName);
                if (l != null) {
                    line = l;
                    return fmt;
                }
            } catch (Exception ignored) {}
        }
        throw new LineUnavailableException("No supported sample rate for selected device");
    }

    private SourceDataLine openLineWithFormat(AudioFormat fmt, String preferredName) throws LineUnavailableException {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        if (preferredName != null && !preferredName.trim().isEmpty() && !"default".equalsIgnoreCase(preferredName)) {
            for (Mixer.Info info : infos) {
                if (info.getName().toLowerCase().contains(preferredName.toLowerCase())) {
                    Mixer m = AudioSystem.getMixer(info);
                    DataLine.Info di = new DataLine.Info(SourceDataLine.class, fmt);
                    if (m.isLineSupported(di)) {
                        SourceDataLine l = (SourceDataLine) m.getLine(di);
                        l.open(fmt);
                        return l;
                    }
                }
            }
        }
        DataLine.Info di = new DataLine.Info(SourceDataLine.class, fmt);
        SourceDataLine l = (SourceDataLine) AudioSystem.getLine(di);
        l.open(fmt);
        return l;
    }

    private int intCfg(String key, int def) {
        Object v = cfg.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    private double numCfg(String key, double def) {
        Object v = cfg.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
        return def;
    }

    private String strCfg(String key, String def) {
        Object v = cfg.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private String describeActiveDevice(SourceDataLine l, String configured) {
        String lineInfo = l == null ? "-" : String.valueOf(l.getLineInfo());
        String cfgDev = configured == null ? "default" : configured;
        return cfgDev + " | " + lineInfo;
    }

    private String detectOccupancyHint(String configured) {
        try {
            String dev = configured == null ? "" : configured.trim().toLowerCase();
            if (!dev.matches("hw:\\d+,\\d+")) return "";
            String[] p = dev.substring(3).split(",");
            int card = Integer.parseInt(p[0]);
            int pcm = Integer.parseInt(p[1]);
            String target = "/dev/snd/pcmC" + card + "D" + pcm + "p";
            Process proc = new ProcessBuilder("bash", "-lc", "fuser -v " + target + " 2>/dev/null || true").start();
            String out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            if (out == null || out.isBlank()) return "";
            String s = out.replaceAll("\\s+", " ").trim();
            if (s.length() > 160) s = s.substring(0, 160) + "...";
            return "设备可能被占用(" + target + "): " + s;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String classifyEndpoint(String text) {
        String s = text == null ? "" : text.toLowerCase();
        if (s.contains("hdmi") || s.contains("display audio")) return "hdmi";
        if (s.contains("spdif") || s.contains("iec958") || s.contains("digital")) return "digital_spdif";
        if (s.contains("usb")) {
            if (s.contains("analog") || s.contains("headphone") || s.contains("line out") || s.contains("speaker")) return "usb_audio_analog_like";
            return "usb_audio_digital_like";
        }
        if (s.contains("analog") || s.contains("headphone") || s.contains("front") || s.contains("line out") || s.contains("speaker")) return "analog";
        return "unknown";
    }

    private String classifyChannelRole(String text) {
        String s = text == null ? "" : text.toLowerCase();
        if (s.contains("headphone")) return "headphone";
        if (s.contains("front")) return "front";
        if (s.contains("line out")) return "lineout";
        if (s.contains("speaker")) return "speaker";
        if (s.contains("spdif") || s.contains("digital") || s.contains("hdmi")) return "digital";
        return "unknown";
    }

    private String matchModeOf(String configured, String matched) {
        String c = configured == null ? "" : configured.trim().toLowerCase();
        String m = matched == null ? "" : matched.trim().toLowerCase();
        if (c.isBlank() || "default".equals(c)) return "fallback";
        if (m.contains(c) || c.contains(m)) return "exact";
        return "fuzzy";
    }

    private int computeMatchScore(String configured, String matched, boolean openable) {
        int score = 0;
        if (openable) score += 40;
        String mode = matchModeOf(configured, matched);
        if ("exact".equals(mode)) score += 40;
        else if ("fuzzy".equals(mode)) score += 25;
        String ep = classifyEndpoint(matched);
        if ("analog".equals(ep) || "usb_audio_analog_like".equals(ep)) score += 20;
        if ("hdmi".equals(ep) || "digital_spdif".equals(ep) || "usb_audio_digital_like".equals(ep)) score -= 10;
        return Math.max(0, Math.min(100, score));
    }

    private String buildWarning(String endpoint, String configured, String matched, String err) {
        if (err != null && !err.isBlank()) return err;
        if ("hdmi".equals(endpoint) || "digital_spdif".equals(endpoint) || "usb_audio_digital_like".equals(endpoint)) {
            return "当前命中数字输出口，LTC建议使用模拟输出（Analog/Headphone/Front/Line Out）";
        }
        if ("fallback".equals(matchModeOf(configured, matched))) {
            return "当前为回退命中，建议确认目标设备是否符合预期";
        }
        return "";
    }

    private static class Timecode {
        int hh, mm, ss, frame;
    }

    private enum FrameRateMode {
        FPS_24(24.0, false),
        FPS_25(25.0, false),
        FPS_2997_DF(29.97, true),
        FPS_30(30.0, false);

        final double rateFps;
        final boolean dropFrame;
        final int nominalFps;
        final double frameDurationSec;

        FrameRateMode(double rateFps, boolean dropFrame) {
            this.rateFps = rateFps;
            this.dropFrame = dropFrame;
            this.nominalFps = (int) Math.round(rateFps);
            this.frameDurationSec = 1.0 / rateFps;
        }

        static FrameRateMode fromConfig(Object v) {
            double fps = 25.0;
            if (v instanceof Number) fps = ((Number) v).doubleValue();
            else {
                try { fps = Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
            }
            if (Math.abs(fps - 29.97) < 0.05) return FPS_2997_DF;
            if (Math.abs(fps - 30.0) < 0.1) return FPS_30;
            if (Math.abs(fps - 24.0) < 0.1) return FPS_24;
            return FPS_25;
        }

        Timecode timecodeFromSeconds(double sec) {
            Timecode t = new Timecode();
            if (dropFrame) {
                long totalFrames = (long) Math.floor(Math.max(0.0, sec) * 30000.0 / 1001.0);
                long d = totalFrames / 17982;
                long m = totalFrames % 17982;
                long dropped = 18L * d + 2L * Math.max(0, (int) ((m - 2) / 1798));
                long frameNumber = totalFrames + dropped;
                t.hh = (int) ((frameNumber / 108000) % 24);
                t.mm = (int) ((frameNumber / 1800) % 60);
                t.ss = (int) ((frameNumber / 30) % 60);
                t.frame = (int) (frameNumber % 30);
                return t;
            }
            long totalFrames = (long) Math.floor(Math.max(0.0, sec) * rateFps);
            t.hh = (int) ((totalFrames / (nominalFps * 3600L)) % 24);
            t.mm = (int) ((totalFrames / (nominalFps * 60L)) % 60);
            t.ss = (int) ((totalFrames / nominalFps) % 60);
            t.frame = (int) (totalFrames % nominalFps);
            return t;
        }
    }

    private static class LtcFrameBuilder {
        private final FrameRateMode mode;

        LtcFrameBuilder(FrameRateMode mode) { this.mode = mode; }

        boolean[] buildBits(Timecode tc) {
            boolean[] bits = new boolean[80];
            int frame = Math.max(0, tc.frame);
            int sec = Math.max(0, tc.ss);
            int min = Math.max(0, tc.mm);
            int hour = Math.max(0, tc.hh);

            // Frames
            writeBcdUnits(bits, 0, frame % 10);
            writeBcdTens(bits, 8, frame / 10, 2);
            bits[10] = false;                 // color frame
            bits[11] = mode.dropFrame;        // drop-frame flag

            // Seconds
            writeBcdUnits(bits, 16, sec % 10);
            writeBcdTens(bits, 24, sec / 10, 3);

            // Minutes
            writeBcdUnits(bits, 32, min % 10);
            writeBcdTens(bits, 40, min / 10, 3);

            // Hours
            writeBcdUnits(bits, 48, hour % 10);
            writeBcdTens(bits, 56, hour / 10, 2);

            // Sync word 16 bits（按线序写入，避免位序方向歧义）
            // 线序目标：0011111111111101
            final int[] syncWireOrder = new int[] {0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,1};
            for (int i = 0; i < 16; i++) bits[64 + i] = syncWireOrder[i] == 1;
            return bits;
        }

        private void writeBcdUnits(boolean[] bits, int start, int v) {
            for (int i = 0; i < 4; i++) bits[start + i] = ((v >> i) & 1) == 1;
        }

        private void writeBcdTens(boolean[] bits, int start, int v, int width) {
            for (int i = 0; i < width; i++) bits[start + i] = ((v >> i) & 1) == 1;
        }
    }

    private static class LtcBmcModulator {
        private final double samplesPerBit;
        private final double halfSamples;
        private boolean[] bits = new boolean[80];
        private int bitIndex = 0;
        private double sampleInBit = 0.0;
        private double level = -1.0;
        private boolean empty = true;

        LtcBmcModulator(int sampleRate, double bitRate) {
            this.samplesPerBit = sampleRate / bitRate;
            this.halfSamples = this.samplesPerBit * 0.5;
        }

        boolean isFrameEmpty() { return empty; }

        void loadFrame(boolean[] b) {
            if (b == null || b.length != 80) return;
            this.bits = b;
            this.bitIndex = 0;
            this.sampleInBit = 0.0;
            this.empty = false;
            // 关键：不在 load 时额外翻转，避免与 bit-start 翻转重复导致时序异常。
        }

        double nextSample() {
            if (empty) return 0.0;

            // BMC 规则1：每个 bit 起始处必翻转
            if (sampleInBit == 0.0) {
                level = -level;
            }

            // BMC 规则2：bit=1 时在半 bit 处再翻转一次
            if (bits[bitIndex]) {
                if (sampleInBit >= halfSamples && (sampleInBit - 1.0) < halfSamples) {
                    level = -level;
                }
            }

            double out = level;

            sampleInBit += 1.0;
            if (sampleInBit >= samplesPerBit) {
                sampleInBit -= samplesPerBit;
                bitIndex++;
                if (bitIndex >= 80) bitIndex = 0;
                if (sampleInBit < 1e-9) sampleInBit = 0.0;
            }
            return out;
        }
    }

    private String toTimecode(double sec, int fps) {
        int total = (int) Math.max(0, Math.floor(sec));
        int hh = total / 3600;
        int mm = (total % 3600) / 60;
        int ss = total % 60;
        int ff = Math.max(0, Math.min(Math.max(1, fps) - 1, frameInSecond));
        return String.format("%02d:%02d:%02d:%02d", hh, mm, ss, ff);
    }
}
