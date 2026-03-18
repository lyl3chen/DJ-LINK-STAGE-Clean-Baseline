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
            System.out.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
        } catch (Exception e) {
            this.lastError = "Load error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            System.err.println("[BasicLocalPlaybackEngine] ====== LOAD FAILED ======");
            System.err.println("[BasicLocalPlaybackEngine] configuredDevice=" + configuredDevice);
            System.err.println("[BasicLocalPlaybackEngine] Error: " + this.lastError);
            e.printStackTrace();
        }
    }

    @Override
    public void play() {
        System.out.println("[BasicLocalPlaybackEngine] play() called, currentState=" + currentState + ", audioLine=" + audioLine + ", currentTrack=" + currentTrack);
        
        if (audioLine == null || currentTrack == null) {
            System.err.println("[BasicLocalPlaybackEngine] No track loaded, audioLine=" + audioLine + ", currentTrack=" + currentTrack);
            return;
        }

        if (currentState == PlaybackStatus.State.PLAYING) {
            System.out.println("[BasicLocalPlaybackEngine] Already playing");
            return; // 已经在播放
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
        
        // 【修复首次播放杂音】先预填充缓冲区，再启动播放
        // 使用 mark/reset 让 playbackLoop 能从正确位置继续
        try {
            if (audioStream.markSupported()) {
                audioStream.mark(16384); // 标记当前位置
            }
            byte[] preBuffer = new byte[8192]; // 预填充 8KB
            int preFilled = 0;
            int targetPreFill = 8192;
            while (preFilled < targetPreFill) {
                int read = audioStream.read(preBuffer, 0, Math.min(preBuffer.length, targetPreFill - preFilled));
                if (read <= 0) break;
                audioLine.write(preBuffer, 0, read);
                preFilled += read;
            }
            // 重置流位置，让 playbackLoop 从头读取
            if (audioStream.markSupported()) {
                audioStream.reset();
                System.out.println("[BasicLocalPlaybackEngine] Pre-filled " + preFilled + " bytes, stream reset for playback loop");
            } else {
                System.out.println("[BasicLocalPlaybackEngine] Pre-filled " + preFilled + " bytes (mark not supported, continuing from current position)");
            }
        } catch (Exception e) {
            System.err.println("[BasicLocalPlaybackEngine] Pre-fill warning: " + e.getMessage());
        }
        
        audioLine.start();

        // 启动播放线程
        playbackThread = new Thread(this::playbackLoop, "local-playback");
        playbackThread.start();

        System.out.println("[BasicLocalPlaybackEngine] Playing: " + currentTrack.getTitle() + ", thread started");
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
        
        if (currentState == PlaybackStatus.State.STOPPED) {
            System.out.println("[BasicLocalPlaybackEngine] Already stopped, returning");
            return;
        }

        currentState = PlaybackStatus.State.STOPPED;
        currentPositionMs = 0;
        pausePositionMs = 0;

        if (audioLine != null) {
            audioLine.stop();
            audioLine.flush();
        }

        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
            System.out.println("[BasicLocalPlaybackEngine] Playback thread interrupted");
        }

        // 关闭音频流
        if (audioStream != null) {
            try {
                audioStream.close();
            } catch (IOException e) {
                System.err.println("[BasicLocalPlaybackEngine] Error closing audio stream: " + e.getMessage());
            }
            audioStream = null;
        }

        // 关闭音频线
        if (audioLine != null) {
            audioLine.close();
            audioLine = null;
            System.out.println("[BasicLocalPlaybackEngine] Audio line closed");
        }

        System.out.println("[BasicLocalPlaybackEngine] Stopped");
    }

    @Override
    public void seek(long positionMs) {
        System.out.println("[BasicLocalPlaybackEngine] Seek to " + positionMs + "ms (simplified: not supported in v1)");
        
        // 第一版简化：不支持精确 seek
        // 实际实现需要重新打开流并跳过指定字节数
        
        // 如果正在播放，停止后重新定位
        boolean wasPlaying = (currentState == PlaybackStatus.State.PLAYING);
        stop();
        this.currentPositionMs = positionMs;
        this.pausePositionMs = positionMs;

        if (wasPlaying) {
            play();
        }
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
