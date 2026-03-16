package dbclient.media.player;

import dbclient.media.model.PlaybackStatus;
import dbclient.media.model.TrackInfo;

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

    @Override
    public void load(TrackInfo track) {
        stop();
        this.currentTrack = track;
        this.currentState = PlaybackStatus.State.STOPPED;
        this.currentPositionMs = 0;
        this.pausePositionMs = 0;

        try {
            File audioFile = new File(track.getFilePath());
            if (!audioFile.exists()) {
                System.err.println("[BasicLocalPlaybackEngine] File not found: " + track.getFilePath());
                return;
            }

            // 获取音频输入流
            audioStream = AudioSystem.getAudioInputStream(audioFile);
            AudioFormat baseFormat = audioStream.getFormat();

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

            // 如果不是 PCM，需要转换
            if (!baseFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)) {
                audioStream = AudioSystem.getAudioInputStream(decodedFormat, audioStream);
            }

            // 打开音频线
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, decodedFormat);
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(decodedFormat);

            System.out.println("[BasicLocalPlaybackEngine] Loaded: " + track.getTitle());

        } catch (UnsupportedAudioFileException e) {
            System.err.println("[BasicLocalPlaybackEngine] Unsupported format: " + e.getMessage());
            System.err.println("[BasicLocalPlaybackEngine] Currently supports: WAV files");
        } catch (Exception e) {
            System.err.println("[BasicLocalPlaybackEngine] Load error: " + e.getMessage());
        }
    }

    @Override
    public void play() {
        if (audioLine == null || currentTrack == null) {
            System.err.println("[BasicLocalPlaybackEngine] No track loaded");
            return;
        }

        if (currentState == PlaybackStatus.State.PLAYING) {
            return; // 已经在播放
        }

        // 如果是从暂停恢复，需要重新定位
        if (currentState == PlaybackStatus.State.PAUSED) {
            // TODO: 重新定位到 pausePositionMs
            // Java Sound 重新定位比较复杂，第一版简化：从头开始
            System.out.println("[BasicLocalPlaybackEngine] Resume from pause (simplified: restart from position)");
        }

        currentState = PlaybackStatus.State.PLAYING;
        startTimeMs = System.currentTimeMillis() - currentPositionMs;
        audioLine.start();

        // 启动播放线程
        playbackThread = new Thread(this::playbackLoop, "local-playback");
        playbackThread.start();

        System.out.println("[BasicLocalPlaybackEngine] Playing: " + currentTrack.getTitle());
    }

    @Override
    public void pause() {
        if (currentState != PlaybackStatus.State.PLAYING) {
            return;
        }

        currentState = PlaybackStatus.State.PAUSED;
        pausePositionMs = currentPositionMs;
        audioLine.stop();

        System.out.println("[BasicLocalPlaybackEngine] Paused at " + pausePositionMs + "ms");
    }

    @Override
    public void stop() {
        if (currentState == PlaybackStatus.State.STOPPED) {
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
        }

        System.out.println("[BasicLocalPlaybackEngine] Stopped");
    }

    @Override
    public void seek(long positionMs) {
        // 第一版简化：不支持精确 seek
        // 实际实现需要重新打开流并跳过指定字节数
        System.out.println("[BasicLocalPlaybackEngine] Seek to " + positionMs + "ms (simplified: not supported in v1)");

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
            // 限制不超过总时长
            if (currentPositionMs > currentTrack.getDurationMs()) {
                currentPositionMs = currentTrack.getDurationMs();
                // 自动停止
                stop();
            }
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

    @Override
    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    @Override
    public void close() {
        stop();
        if (audioLine != null) {
            audioLine.close();
            audioLine = null;
        }
        if (audioStream != null) {
            try {
                audioStream.close();
            } catch (IOException e) {
                // ignore
            }
            audioStream = null;
        }
    }

    @Override
    public String getEngineName() {
        return "BasicLocalPlaybackEngine (Java Sound API)";
    }

    /**
     * 播放循环线程
     */
    private void playbackLoop() {
        byte[] buffer = new byte[4096];

        try {
            while (currentState == PlaybackStatus.State.PLAYING && !Thread.interrupted()) {
                int bytesRead = audioStream.read(buffer);
                if (bytesRead == -1) {
                    // 播放结束
                    break;
                }

                if (bytesRead > 0) {
                    audioLine.write(buffer, 0, bytesRead);
                }
            }

            // 播放结束或停止
            if (currentState == PlaybackStatus.State.PLAYING) {
                // 自然播放结束
                audioLine.drain();
                currentState = PlaybackStatus.State.STOPPED;
                currentPositionMs = currentTrack.getDurationMs();
                System.out.println("[BasicLocalPlaybackEngine] Playback finished");
            }

        } catch (Exception e) {
            if (currentState != PlaybackStatus.State.STOPPED) {
                System.err.println("[BasicLocalPlaybackEngine] Playback error: " + e.getMessage());
                currentState = PlaybackStatus.State.STOPPED;
            }
        }
    }

    /**
     * 检查是否支持该文件
     */
    public static boolean isSupported(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.endsWith(".wav") || lower.endsWith(".aiff") || lower.endsWith(".au");
    }
}
