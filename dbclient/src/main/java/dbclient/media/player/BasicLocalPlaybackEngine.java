package dbclient.media.player;

import dbclient.media.model.PlaybackStatus;
import dbclient.media.model.TrackInfo;
import dbclient.sync.drivers.AudioDeviceEnumerator;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

/**
 * 基础本地播放引擎实现
 * 使用 Java Sound API 内部实现，对外只暴露标准接口和我们的 DTO
 *
 * 当前支持格式：WAV（最可靠）
 * 限制：MP3 需要额外解码器（如 JLayer），第一版先以 WAV 为主
 */
public class BasicLocalPlaybackEngine implements PlaybackEngine {

    private SourceDataLine audioLine;
    private AudioInputStream audioStream;
    private TrackInfo currentTrack;
    private volatile PlaybackStatus.State currentState = PlaybackStatus.State.STOPPED;
    private volatile long currentPositionMs = 0;
    private volatile long startTimeMs = 0;
    private volatile long pausePositionMs = 0;
    private Thread playbackThread;
    private volatile String deviceName = "default"; // 音频输出设备
    private volatile String actualOpenedDevice = null; // 实际成功打开的设备
    private volatile String lastError = null; // 最后一次错误信息
    private static volatile boolean shutdownHookRegistered = false;

    public BasicLocalPlaybackEngine() {
        registerShutdownHook();
    }

    private void registerShutdownHook() {
        if (shutdownHookRegistered) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[BasicLocalPlaybackEngine] Shutdown hook: force cleanup...");
            forceCleanup();
        }, "audio-cleanup"));
        shutdownHookRegistered = true;
    }

    @Override
    public synchronized void load(TrackInfo track) {
        String configuredDevice = this.deviceName; // 记录配置的设备名
        System.out.println("[BasicLocalPlaybackEngine] ====== LOAD START ======");
        System.out.println("[BasicLocalPlaybackEngine] configuredDevice=" + configuredDevice);
        System.out.println("[BasicLocalPlaybackEngine] track=" + track.getTitle());

        // 【根因修复】加载前彻底清理，防止设备占用残留
        forceCleanup();
        
        // 短暂等待让操作系统释放音频设备
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        this.currentTrack = null; // 先清空，成功后再设置
        this.currentState = PlaybackStatus.State.STOPPED;
        this.currentPositionMs = 0;
        this.pausePositionMs = 0;
        this.actualOpenedDevice = null;
        this.lastError = null;

        try {
            File audioFile = new File(track.getFilePath());
            if (!audioFile.exists()) {
                this.lastError = "File not found: " + track.getFilePath();
                System.err.println("[BasicLocalPlaybackEngine] " + this.lastError);
                System.out.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
                return;
            }

            // 获取音频输入流
            audioStream = AudioSystem.getAudioInputStream(audioFile);
            AudioFormat baseFormat = audioStream.getFormat();
            System.out.println("[BasicLocalPlaybackEngine] Source format: " + baseFormat);

            // 尝试多种格式，优先使用设备原生支持的格式
            AudioFormat targetFormat = findSupportedFormat(configuredDevice, baseFormat);
            if (targetFormat == null) {
                this.lastError = "No compatible audio format found for device: " + configuredDevice + 
                    ". Tried: 48000/16-bit/stereo, 44100/16-bit/stereo, 48000/16-bit/mono, 44100/16-bit/mono";
                System.err.println("[BasicLocalPlaybackEngine] " + this.lastError);
                cleanupAfterFailure();
                System.out.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
                return;
            }
            
            System.out.println("[BasicLocalPlaybackEngine] Selected target format: " + targetFormat);

            // 如果不是目标格式，需要转换
            if (!baseFormat.matches(targetFormat)) {
                audioStream = AudioSystem.getAudioInputStream(targetFormat, audioStream);
            }

            // 打开音频线（使用指定设备）
            System.out.println("[BasicLocalPlaybackEngine] Attempting to open device: '" + configuredDevice + "'");
            System.out.println("[BasicLocalPlaybackEngine] Target format: " + targetFormat);

            // 获取 mixer 信息用于诊断
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            System.out.println("[BasicLocalPlaybackEngine] Available mixers:");
            for (Mixer.Info info : mixerInfos) {
                System.out.println("  - " + info.getName() + " (" + info.getDescription() + ")");
            }

            // 检查设备是否支持目标格式
            if (!isFormatSupported(configuredDevice, targetFormat)) {
                this.lastError = "Selected device '" + configuredDevice + "' does not support format: " + targetFormat +
                    ". Device may not support " + (int)targetFormat.getSampleRate() + "Hz " + targetFormat.getChannels() + "-channel PCM";
                System.err.println("[BasicLocalPlaybackEngine] " + this.lastError);
                cleanupAfterFailure();
                System.out.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
                return;
            }

            audioLine = AudioDeviceEnumerator.getSourceDataLine(configuredDevice, targetFormat);

            // 获取实际使用的 mixer 名称
            Mixer actualMixer = AudioDeviceEnumerator.findMixerByName(configuredDevice);
            String actualMixerName = actualMixer != null ? actualMixer.getMixerInfo().getName() : "unknown";
            System.out.println("[BasicLocalPlaybackEngine] Selected mixer: " + actualMixerName);

            audioLine.open(targetFormat);

            // 成功打开后记录
            this.actualOpenedDevice = configuredDevice;
            this.currentTrack = track;
            this.lastError = null;

            System.out.println("[BasicLocalPlaybackEngine] ====== LOAD SUCCESS ======");
            System.out.println("[BasicLocalPlaybackEngine] configuredDevice=" + configuredDevice);
            System.out.println("[BasicLocalPlaybackEngine] actualOpenedDevice=" + this.actualOpenedDevice);
            System.out.println("[BasicLocalPlaybackEngine] actualMixer=" + actualMixerName);
            System.out.println("[BasicLocalPlaybackEngine] audioLine=" + audioLine);
            System.out.println("[BasicLocalPlaybackEngine] audioLine format=" + audioLine.getFormat());

        } catch (UnsupportedAudioFileException e) {
            this.lastError = "Unsupported format: " + e.getMessage();
            System.err.println("[BasicLocalPlaybackEngine] " + this.lastError);
            cleanupAfterFailure(); // 【根因修复】失败时彻底清理
            System.out.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
            return;
        } catch (Exception e) {
            this.lastError = "设备打开失败: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            System.err.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
            System.err.println("[BasicLocalPlaybackEngine] configuredDevice=" + configuredDevice);
            System.err.println("[BasicLocalPlaybackEngine] Error: " + this.lastError);
            e.printStackTrace();
            cleanupAfterFailure(); // 【根因修复】失败时彻底清理
            System.out.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
            return;
        }
    }

    /**
     * 格式协商：尝试多种格式，返回设备支持的第一个格式
     */
    private AudioFormat findSupportedFormat(String deviceName, AudioFormat sourceFormat) {
        // 优先尝试源格式
        if (isFormatSupported(deviceName, sourceFormat)) {
            System.out.println("[BasicLocalPlaybackEngine] Source format supported: " + sourceFormat);
            return sourceFormat;
        }
        
        // 格式协商顺序
        float[] sampleRates = { sourceFormat.getSampleRate(), 48000f, 44100f };
        int[] bitDepths = { 16 };
        int[] channels = { sourceFormat.getChannels(), 2, 1 };
        
        for (float sr : sampleRates) {
            for (int bits : bitDepths) {
                for (int ch : channels) {
                    if (sr <= 0 || bits <= 0 || ch <= 0) continue;
                    
                    AudioFormat format = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        sr, bits, ch, ch * (bits / 8), sr, false
                    );
                    
                    if (isFormatSupported(deviceName, format)) {
                        System.out.println("[BasicLocalPlaybackEngine] Found supported format: " + format);
                        return format;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 检查指定设备是否支持并能实际打开指定格式
     * 注意：isLineSupported() 只检查格式是否被 mixer 支持，不检查 line 是否实际可用
     */
    private boolean isFormatSupported(String deviceName, AudioFormat format) {
        SourceDataLine testLine = null;
        try {
            Mixer mixer = AudioDeviceEnumerator.findMixerByName(deviceName);
            if (mixer == null) {
                System.out.println("[BasicLocalPlaybackEngine] Mixer not found: " + deviceName);
                return false;
            }
            
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            
            if (!mixer.isLineSupported(info)) {
                System.out.println("[BasicLocalPlaybackEngine] Format NOT supported by " + deviceName + ": " + format);
                return false;
            }
            
            // 【关键】真正尝试打开 line 来验证可用性
            testLine = AudioDeviceEnumerator.getSourceDataLine(deviceName, format);
            testLine.open(format);
            testLine.close(); // 立即关闭，只是测试
            
            System.out.println("[BasicLocalPlaybackEngine] Format verified (can open): " + deviceName + ": " + format);
            return true;
            
        } catch (LineUnavailableException e) {
            System.out.println("[BasicLocalPlaybackEngine] Format available but line in use: " + deviceName + ": " + format + " - " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.out.println("[BasicLocalPlaybackEngine] Error verifying format: " + deviceName + ": " + format + " - " + e.getMessage());
            return false;
        } finally {
            if (testLine != null) {
                try { testLine.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 【根因修复】失败后的彻底清理，防止设备占用和状态污染
     */
    private void cleanupAfterFailure() {
        System.out.println("[BasicLocalPlaybackEngine] Cleaning up after failure...");

        // 停止播放线程
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(500);
            } catch (InterruptedException ignored) {}
            playbackThread = null;
        }

        // 清理 audioStream
        if (audioStream != null) {
            try {
                audioStream.close();
            } catch (IOException ignored) {}
            audioStream = null;
        }

        // 清理 audioLine（先 stop 再 close）
        if (audioLine != null) {
            try {
                if (audioLine.isOpen()) {
                    audioLine.stop();
                    audioLine.flush();
                }
            } catch (Exception ignored) {}
            try {
                audioLine.close();
            } catch (Exception ignored) {}
            audioLine = null;
        }

        // 重置状态
        this.actualOpenedDevice = null;
        this.currentTrack = null;
        this.currentState = PlaybackStatus.State.STOPPED;
        this.currentPositionMs = 0;
        this.pausePositionMs = 0;

        System.out.println("[BasicLocalPlaybackEngine] Cleanup completed");
    }

    /**
     * 【根因修复】强制彻底清理，防止设备占用残留
     * 比 stop() 更激进，在加载新曲目前调用
     */
    private synchronized void forceCleanup() {
        System.out.println("[BasicLocalPlaybackEngine] Force cleanup starting...");
        
        // 停止播放状态
        currentState = PlaybackStatus.State.STOPPED;
        
        // 停止播放线程
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(1000); // 等待最多1秒
            } catch (InterruptedException ignored) {}
            playbackThread = null;
        }
        
        // 停止并关闭 audioLine
        if (audioLine != null) {
            try {
                if (audioLine.isOpen()) {
                    audioLine.stop();
                    audioLine.flush();
                    audioLine.close();
                    System.out.println("[BasicLocalPlaybackEngine] Audio line force closed");
                }
            } catch (Exception e) {
                System.err.println("[BasicLocalPlaybackEngine] Error force closing line: " + e.getMessage());
            }
            audioLine = null;
        }
        
        // 关闭 audioStream
        if (audioStream != null) {
            try {
                audioStream.close();
                System.out.println("[BasicLocalPlaybackEngine] Audio stream force closed");
            } catch (Exception e) {
                System.err.println("[BasicLocalPlaybackEngine] Error force closing stream: " + e.getMessage());
            }
            audioStream = null;
        }
        
        System.out.println("[BasicLocalPlaybackEngine] Force cleanup completed");
    }

    @Override
    public synchronized void play() {
        System.out.println("[BasicLocalPlaybackEngine] play() called, currentState=" + currentState + ", audioLine=" + audioLine + ", currentTrack=" + currentTrack);

        if (currentTrack == null) {
            System.err.println("[BasicLocalPlaybackEngine] No track loaded, currentTrack=" + currentTrack);
            return;
        }

        if (currentState == PlaybackStatus.State.PLAYING) {
            System.out.println("[BasicLocalPlaybackEngine] Already playing");
            return;
        }

        // 【根因修复】如果 audioLine 为 null（Stop 后或 Load 失败后），重新初始化
        if (audioLine == null || audioStream == null) {
            System.out.println("[BasicLocalPlaybackEngine] Audio not initialized (line=" + audioLine + ", stream=" + audioStream + "), reinitializing...");
            if (!reinitializeAudio()) {
                System.err.println("[BasicLocalPlaybackEngine] Failed to reinitialize audio");
                return;
            }
        }

        // 检查 audioLine 是否真的打开
        if (!audioLine.isOpen()) {
            System.err.println("[BasicLocalPlaybackEngine] Audio line not open, attempting to reopen...");
            if (!reinitializeAudio()) {
                System.err.println("[BasicLocalPlaybackEngine] Failed to reopen audio");
                return;
            }
        }

        System.out.println("[BasicLocalPlaybackEngine] play() proceeding, audioStream=" + audioStream);

        // 如果是从暂停恢复，需要重新定位
        if (currentState == PlaybackStatus.State.PAUSED) {
            // 【Phase 1 已知限制】Java Sound 重新定位需要重新打开音频流并跳过字节
            // 当前简化实现：从头开始播放。如需精确定位，需在 Phase C 实现。
            System.out.println("[BasicLocalPlaybackEngine] Resume from pause (simplified: restart from position)");
        }

        currentState = PlaybackStatus.State.PLAYING;
        System.out.println("[BasicLocalPlaybackEngine] Setting state to PLAYING, now=" + currentState);
        startTimeMs = System.currentTimeMillis() - currentPositionMs;

        // 启动播放（预填充 + 启动）
        try {
            prefillAndStartPlayback();
        } catch (Exception e) {
            System.err.println("[BasicLocalPlaybackEngine] Play start error: " + e.getMessage());
            this.currentState = PlaybackStatus.State.STOPPED;
            return;
        }

        System.out.println("[BasicLocalPlaybackEngine] Playing: " + currentTrack.getTitle() + ", thread started");
    }

    /**
     * 【修正】重新初始化音频（Stop 后 Play 使用）
     * 保留 currentTrack，只重新打开 audioLine 和 audioStream
     */
    private synchronized boolean reinitializeAudio() {
        return reinitializeAudioAtPositionMs(currentPositionMs);
    }

    /**
     * 重新初始化音频并定位到目标毫秒位置。
     * 这是 seek 的底层核心：保证“显示位置”和“真实播放位置”一致。
     */
    private synchronized boolean reinitializeAudioAtPositionMs(long targetPositionMs) {
        if (currentTrack == null) {
            return false;
        }
        try {
            File audioFile = new File(currentTrack.getFilePath());
            if (!audioFile.exists()) {
                this.lastError = "File not found: " + currentTrack.getFilePath();
                return false;
            }

            // 先关闭旧资源，避免设备占用残留
            if (audioStream != null) {
                try { audioStream.close(); } catch (IOException ignored) {}
                audioStream = null;
            }
            if (audioLine != null) {
                try { audioLine.close(); } catch (Exception ignored) {}
                audioLine = null;
            }

            AudioInputStream newStream = AudioSystem.getAudioInputStream(audioFile);
            AudioFormat baseFormat = newStream.getFormat();
            
            // 使用格式协商找到支持的格式
            AudioFormat targetFormat = findSupportedFormat(deviceName, baseFormat);
            if (targetFormat == null) {
                System.out.println("[BasicLocalPlaybackEngine] reinitializeAudio: no compatible format for device: " + deviceName);
                return false;
            }
            
            // 转换格式
            if (!baseFormat.matches(targetFormat)) {
                newStream = AudioSystem.getAudioInputStream(targetFormat, newStream);
            }

            // 检查设备是否支持目标格式
            if (!isFormatSupported(deviceName, targetFormat)) {
                System.out.println("[BasicLocalPlaybackEngine] reinitializeAudio: device does not support format: " + targetFormat);
                return false;
            }

            // 重新打开音频线
            this.audioLine = AudioDeviceEnumerator.getSourceDataLine(deviceName, targetFormat);
            this.audioLine.open(targetFormat);
            this.actualOpenedDevice = deviceName;

            // ===== seek 核心：毫秒 -> 字节偏移 =====
            int frameSize = Math.max(1, targetFormat.getFrameSize());
            float sampleRate = targetFormat.getSampleRate();
            long targetFrame = Math.max(0L, Math.round((targetPositionMs / 1000.0) * sampleRate));
            long targetBytes = targetFrame * frameSize;

            long skipped = 0;
            while (skipped < targetBytes) {
                long n = newStream.skip(targetBytes - skipped);
                if (n > 0) {
                    skipped += n;
                    continue;
                }
                // skip 可能返回 0，尝试读丢弃 1 字节推进
                int b = newStream.read();
                if (b == -1) {
                    break; // EOF
                }
                skipped += 1;
            }

            // 计算“真实定位到的位置”（避免把期望位置当真实位置）
            long actualFrame = skipped / frameSize;
            long actualPositionMs = (long) ((actualFrame * 1000.0) / sampleRate);
            this.currentPositionMs = actualPositionMs;
            this.pausePositionMs = actualPositionMs;

            this.audioStream = newStream;
            this.lastError = null;

            System.out.println("[BasicLocalPlaybackEngine] Audio reinitialized successfully");
            System.out.println("[BasicLocalPlaybackEngine] seek targetMs=" + targetPositionMs + ", targetBytes=" + targetBytes + ", skipped=" + skipped + ", actualMs=" + actualPositionMs);
            return true;
        } catch (Exception e) {
            this.lastError = "Reinitialize/seek error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            System.err.println("[BasicLocalPlaybackEngine] " + this.lastError);
            return false;
        }
    }

    @Override
    public synchronized void pause() {
        System.out.println("[BasicLocalPlaybackEngine] pause() called");
        if (currentState == PlaybackStatus.State.PLAYING) {
            currentState = PlaybackStatus.State.PAUSED;
            pausePositionMs = currentPositionMs;
            audioLine.stop();
            System.out.println("[BasicLocalPlaybackEngine] Paused at " + pausePositionMs + "ms");
        }
    }

    @Override
    public synchronized void stop() {
        System.out.println("[BasicLocalPlaybackEngine] stop() called, currentState=" + currentState);

        if (currentState == PlaybackStatus.State.STOPPED && audioLine == null) {
            System.out.println("[BasicLocalPlaybackEngine] Already stopped and cleaned up, returning");
            return;
        }

        currentState = PlaybackStatus.State.STOPPED;
        currentPositionMs = 0;
        pausePositionMs = 0;

        if (audioLine != null) {
            try {
                audioLine.stop();
                audioLine.flush();
            } catch (Exception e) {
                System.err.println("[BasicLocalPlaybackEngine] Error stopping audioLine: " + e.getMessage());
            }
        }

        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(500); // 等待最多500ms
            } catch (InterruptedException ignored) {}
            playbackThread = null;
            System.out.println("[BasicLocalPlaybackEngine] Playback thread stopped");
        }

        // 【根因修复】彻底关闭资源，释放设备占用
        // 否则USB设备一直被占用，后续load无法打开
        if (audioStream != null) {
            try {
                audioStream.close();
                System.out.println("[BasicLocalPlaybackEngine] Audio stream closed");
            } catch (IOException e) {
                System.err.println("[BasicLocalPlaybackEngine] Error closing stream: " + e.getMessage());
            }
            audioStream = null;
        }

        if (audioLine != null) {
            try {
                audioLine.close();
                System.out.println("[BasicLocalPlaybackEngine] Audio line closed");
            } catch (Exception e) {
                System.err.println("[BasicLocalPlaybackEngine] Error closing line: " + e.getMessage());
            }
            audioLine = null;
        }

        // 注意：保留currentTrack，Stop后可以立即Play（通过reinitializeAudio）
        System.out.println("[BasicLocalPlaybackEngine] Stopped (currentTrack retained: " + (currentTrack != null ? currentTrack.getTitle() : "null") + ", audio resources released)");
    }

    @Override
    public synchronized void seek(long positionMs) {
        System.out.println("[BasicLocalPlaybackEngine] Seek to " + positionMs + "ms from state=" + currentState);

        // 【修正】Seek 语义：
        // - PLAYING 状态下 seek：跳转后继续播放
        // - PAUSED 状态下 seek：跳转后保持暂停
        // - STOPPED 状态下 seek：跳转后进入 PAUSED（不自动播放）

        if (currentTrack == null) {
            System.err.println("[BasicLocalPlaybackEngine] Cannot seek: no track loaded");
            return;
        }

        // 边界保护
        long durationMs = currentTrack.getDurationMs();
        long clampedPos = positionMs;
        if (durationMs > 0) {
            clampedPos = Math.max(0, Math.min(positionMs, durationMs));
        }

        // 记录原状态
        PlaybackStatus.State originalState = currentState;

        // 停止当前播放（但不卸载）
        if (currentState == PlaybackStatus.State.PLAYING || currentState == PlaybackStatus.State.PAUSED) {
            // 只停止不卸载（保留 currentTrack）
            if (audioLine != null) {
                audioLine.stop();
                audioLine.flush();
            }
            if (playbackThread != null) {
                playbackThread.interrupt();
                playbackThread = null;
            }
        }

        // 统一先做“真实定位”（毫秒->字节偏移），得到 actualPositionMs
        if (!reinitializeAudioAtPositionMs(clampedPos)) {
            this.currentState = PlaybackStatus.State.STOPPED;
            return;
        }

        // 根据原状态决定新状态
        if (originalState == PlaybackStatus.State.PLAYING) {
            // PLAYING -> 继续 PLAYING（从真实定位位置继续播放）
            this.currentState = PlaybackStatus.State.PLAYING;
            System.out.println("[BasicLocalPlaybackEngine] Seek from PLAYING, restarting playback at actual " + currentPositionMs + "ms");
            this.startTimeMs = System.currentTimeMillis() - currentPositionMs;
            try {
                prefillAndStartPlayback();
            } catch (Exception e) {
                System.err.println("[BasicLocalPlaybackEngine] Seek restart error: " + e.getMessage());
                this.currentState = PlaybackStatus.State.PAUSED;
            }
        } else {
            // PAUSED 或 STOPPED -> 进入 PAUSED（不自动播放）
            this.currentState = PlaybackStatus.State.PAUSED;
            System.out.println("[BasicLocalPlaybackEngine] Seek from " + originalState + ", entering PAUSED at actual " + currentPositionMs + "ms");
        }
    }

    /**
     * 预填充并启动播放（抽取公共逻辑）
     */
    private void prefillAndStartPlayback() throws Exception {
        // 预填充缓冲区
        if (audioStream.markSupported()) {
            audioStream.mark(16384);
        }
        byte[] preBuffer = new byte[8192];
        int preFilled = 0;
        int targetPreFill = 8192;
        while (preFilled < targetPreFill) {
            int read = audioStream.read(preBuffer, 0, Math.min(preBuffer.length, targetPreFill - preFilled));
            if (read <= 0) break;
            audioLine.write(preBuffer, 0, read);
            preFilled += read;
        }
        if (audioStream.markSupported()) {
            audioStream.reset();
        }

        audioLine.start();
        playbackThread = new Thread(this::playbackLoop, "local-playback");
        playbackThread.start();
    }

    @Override
    public PlaybackStatus getStatus() {
        // 计算当前位置
        if (currentState == PlaybackStatus.State.PLAYING) {
            currentPositionMs = System.currentTimeMillis() - startTimeMs;
            // 限制不超过总时长（只有当 duration > 0 时才检查）
            if (currentTrack != null && currentTrack.getDurationMs() > 0 && currentPositionMs > currentTrack.getDurationMs()) {
                currentPositionMs = currentTrack.getDurationMs();
                // 自动停止
                stop();
            }
        }

        // 调试：打印状态
        if (currentState == PlaybackStatus.State.PLAYING) {
            System.out.println("[BasicLocalPlaybackEngine] getStatus: PLAYING, position=" + currentPositionMs);
        }

        return PlaybackStatus.builder()
            .state(currentState)
            .positionMs(currentPositionMs)
            .durationMs(currentTrack != null ? currentTrack.getDurationMs() : 0)
            .effectiveBpm(0.0) // BPM 未知时返回 0.0，由上层处理 unknown 策略
            .pitch(1.0)
            .currentTrackId(currentTrack != null ? currentTrack.getTrackId() : null)
            .build();
    }

    /**
     * 获取诊断信息
     */
    public DeviceDiagnostics getDiagnostics() {
        return new DeviceDiagnostics(
            this.deviceName,
            this.actualOpenedDevice,
            this.lastError,
            this.audioLine != null ? this.audioLine.getFormat().toString() : null
        );
    }

    public static class DeviceDiagnostics {
        public final String configuredDevice;
        public final String actualOpenedDevice;
        public final String lastError;
        public final String audioFormat;

        public DeviceDiagnostics(String configuredDevice, String actualOpenedDevice, String lastError, String audioFormat) {
            this.configuredDevice = configuredDevice;
            this.actualOpenedDevice = actualOpenedDevice;
            this.lastError = lastError;
            this.audioFormat = audioFormat;
        }

        public String configuredDevice() { return configuredDevice; }
        public String actualOpenedDevice() { return actualOpenedDevice; }
        public String lastError() { return lastError; }
        public String audioFormat() { return audioFormat; }
    }

    @Override
    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    /**
     * 【修复】清空当前曲目（STOPPED状态下删除当前曲目时使用）
     */
    public void clearCurrentTrack() {
        System.out.println("[BasicLocalPlaybackEngine] clearCurrentTrack() called, currentTrack=" + (currentTrack != null ? currentTrack.getTitle() : "null"));
        this.currentTrack = null;
        this.currentPositionMs = 0;
        this.pausePositionMs = 0;
        // 注意：不清理audioLine/audioStream，因为STOPPED状态下它们已经被清理或保留下次使用
        System.out.println("[BasicLocalPlaybackEngine] Current track cleared");
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public String getEngineName() {
        return "BasicLocalPlaybackEngine";
    }

    @Override
    public void setAudioDevice(String deviceName) {
        String oldDevice = this.deviceName;
        this.deviceName = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "default";
        System.out.println("[BasicLocalPlaybackEngine] Audio device set: " + oldDevice + " -> " + this.deviceName);
    }

    /**
     * 获取当前音频输出设备
     * 返回实际成功打开的设备名，如果没有则返回配置的设备名
     */
    @Override
    public String getAudioDevice() {
        return this.actualOpenedDevice != null ? this.actualOpenedDevice : this.deviceName;
    }

    private void playbackLoop() {
        byte[] buffer = new byte[4096];

        System.out.println("[BasicLocalPlaybackEngine] playbackLoop started, audioStream=" + audioStream + ", audioLine=" + audioLine);

        try {
            int loopCount = 0;
            while (currentState == PlaybackStatus.State.PLAYING && !Thread.interrupted()) {
                if (audioStream == null) {
                    System.err.println("[BasicLocalPlaybackEngine] audioStream is null!");
                    break;
                }

                int bytesRead = audioStream.read(buffer);
                loopCount++;

                if (bytesRead == -1) {
                    // 播放结束
                    System.out.println("[BasicLocalPlaybackEngine] End of stream reached after " + loopCount + " loops");
                    break;
                }

                if (bytesRead > 0) {
                    audioLine.write(buffer, 0, bytesRead);

                    // 计算实时延迟：确保以实际播放速度输出
                    // bytesPerSecond = sampleRate * channels * bytesPerSample
                    if (audioLine.getFormat() != null) {
                        double sampleRate = audioLine.getFormat().getSampleRate();
                        int channels = audioLine.getFormat().getChannels();
                        int bytesPerSample = audioLine.getFormat().getSampleSizeInBits() / 8;
                        double bytesPerSecond = sampleRate * channels * bytesPerSample;
                        long delayMs = (long) (bytesRead / bytesPerSecond * 1000);
                        if (delayMs > 0) {
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                }

                if (loopCount % 100 == 0) {
                    System.out.println("[BasicLocalPlaybackEngine] playbackLoop iteration " + loopCount + ", bytesRead=" + bytesRead);
                }
            }

            // 播放结束或停止
            if (currentState == PlaybackStatus.State.PLAYING) {
                // 自然播放结束
                audioLine.drain();
                currentState = PlaybackStatus.State.STOPPED;
                currentPositionMs = currentTrack.getDurationMs();
                System.out.println("[BasicLocalPlaybackEngine] Playback finished naturally");
            } else {
                System.out.println("[BasicLocalPlaybackEngine] Playback stopped, state=" + currentState + ", interrupted=" + Thread.interrupted());
            }

        } catch (Exception e) {
            System.err.println("[BasicLocalPlaybackEngine] Playback error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
