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

    @Override
    public void load(TrackInfo track) {
        String configuredDevice = this.deviceName; // 记录配置的设备名
        System.out.println("[BasicLocalPlaybackEngine] ====== LOAD START ======");
        System.out.println("[BasicLocalPlaybackEngine] configuredDevice=" + configuredDevice);
        System.out.println("[BasicLocalPlaybackEngine] track=" + track.getTitle());

        stop();
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

            // 转换为标准格式（16-bit signed PCM）
            AudioFormat decodedFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                16,
                baseFormat.getChannels(),
                baseFormat.getChannels() * 2,
                baseFormat.getSampleRate(),
                false
            );
            System.out.println("[BasicLocalPlaybackEngine] Target format: " + decodedFormat);

            // 如果不是 PCM，需要转换
            if (!baseFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)) {
                audioStream = AudioSystem.getAudioInputStream(decodedFormat, audioStream);
            }

            // 打开音频线（使用指定设备）
            System.out.println("[BasicLocalPlaybackEngine] Attempting to open device: '" + configuredDevice + "'");

            // 获取 mixer 信息用于诊断
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            System.out.println("[BasicLocalPlaybackEngine] Available mixers:");
            for (Mixer.Info info : mixerInfos) {
                System.out.println("  - " + info.getName() + " (" + info.getDescription() + ")");
            }

            audioLine = AudioDeviceEnumerator.getSourceDataLine(configuredDevice, decodedFormat);

            // 获取实际使用的 mixer 名称
            Mixer actualMixer = AudioDeviceEnumerator.findMixerByName(configuredDevice);
            String actualMixerName = actualMixer != null ? actualMixer.getMixerInfo().getName() : "unknown";
            System.out.println("[BasicLocalPlaybackEngine] Selected mixer: " + actualMixerName);

            audioLine.open(decodedFormat);

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
     * 【根因修复】失败后的彻底清理，防止设备占用和状态污染
     */
    private void cleanupAfterFailure() {
        System.out.println("[BasicLocalPlaybackEngine] Cleaning up after failure...");

        // 清理 audioStream
        if (audioStream != null) {
            try {
                audioStream.close();
            } catch (IOException ignored) {}
            audioStream = null;
        }

        // 清理 audioLine（即使半打开也要关闭）
        if (audioLine != null) {
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

    @Override
    public void play() {
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
    private boolean reinitializeAudio() {
        if (currentTrack == null) {
            return false;
        }
        try {
            File audioFile = new File(currentTrack.getFilePath());
            if (!audioFile.exists()) {
                this.lastError = "File not found: " + currentTrack.getFilePath();
                return false;
            }

            // 重新获取音频输入流
            AudioInputStream newStream = AudioSystem.getAudioInputStream(audioFile);
            AudioFormat baseFormat = newStream.getFormat();
            AudioFormat decodedFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                16,
                baseFormat.getChannels(),
                baseFormat.getChannels() * 2,
                baseFormat.getSampleRate(),
                false
            );
            if (!baseFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)) {
                newStream = AudioSystem.getAudioInputStream(decodedFormat, newStream);
            }
            this.audioStream = newStream;

            // 重新打开音频线
            this.audioLine = AudioDeviceEnumerator.getSourceDataLine(deviceName, decodedFormat);
            this.audioLine.open(decodedFormat);
            this.actualOpenedDevice = deviceName;

            System.out.println("[BasicLocalPlaybackEngine] Audio reinitialized successfully");
            return true;
        } catch (Exception e) {
            this.lastError = "Reinitialize error: " + e.getMessage();
            System.err.println("[BasicLocalPlaybackEngine] " + this.lastError);
            return false;
        }
    }

    @Override
    public void pause() {
        System.out.println("[BasicLocalPlaybackEngine] pause() called");
        if (currentState == PlaybackStatus.State.PLAYING) {
            currentState = PlaybackStatus.State.PAUSED;
            pausePositionMs = currentPositionMs;
            audioLine.stop();
            System.out.println("[BasicLocalPlaybackEngine] Paused at " + pausePositionMs + "ms");
        }
    }

    @Override
    public void stop() {
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
    public void seek(long positionMs) {
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

        // 设置新位置
        this.currentPositionMs = clampedPos;
        this.pausePositionMs = clampedPos;

        // 根据原状态决定新状态
        if (originalState == PlaybackStatus.State.PLAYING) {
            // PLAYING -> 继续 PLAYING（重新启动播放）
            this.currentState = PlaybackStatus.State.PLAYING;
            System.out.println("[BasicLocalPlaybackEngine] Seek from PLAYING, restarting playback at " + clampedPos + "ms");

            // 重新初始化音频（如果需要）并播放
            if (audioLine == null || audioStream == null) {
                if (!reinitializeAudio()) {
                    this.currentState = PlaybackStatus.State.STOPPED;
                    return;
                }
            }

            // 重新启动播放线程
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
            System.out.println("[BasicLocalPlaybackEngine] Seek from " + originalState + ", entering PAUSED at " + clampedPos + "ms");
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
