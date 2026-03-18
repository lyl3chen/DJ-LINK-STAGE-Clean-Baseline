package dbclient.media.player;

import dbclient.media.model.PlaybackStatus;
import dbclient.media.model.TrackInfo;
import org.junit.After;
import org.junit.Test;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

public class BasicLocalPlaybackEngineStateTest {

    private final BasicLocalPlaybackEngine engine = new BasicLocalPlaybackEngine();

    @After
    public void tearDown() {
        try { engine.stop(); } catch (Exception ignored) {}
    }

    @Test
    public void shouldTransitionStoppedPlayingPausedPlayingStopped() throws Exception {
        preparePlayableState(1024 * 256);

        // STOPPED -> PLAYING
        engine.play();
        Thread.sleep(120);
        assertEquals(PlaybackStatus.State.PLAYING, engine.getStatus().getState());

        // PLAYING -> PAUSED
        engine.pause();
        Thread.sleep(30);
        assertEquals(PlaybackStatus.State.PAUSED, engine.getStatus().getState());

        // PAUSED -> PLAYING
        prepareStreamOnly(1024 * 256); // simulate resume source availability
        engine.play();
        Thread.sleep(120);
        assertEquals(PlaybackStatus.State.PLAYING, engine.getStatus().getState());

        // PLAYING -> STOPPED
        engine.stop();
        assertEquals(PlaybackStatus.State.STOPPED, engine.getStatus().getState());
    }

    @Test
    public void shouldTransitionPausedToStopped() throws Exception {
        preparePlayableState(1024 * 128);
        engine.play();
        Thread.sleep(80);
        engine.pause();
        assertEquals(PlaybackStatus.State.PAUSED, engine.getStatus().getState());

        engine.stop();
        assertEquals(PlaybackStatus.State.STOPPED, engine.getStatus().getState());
    }

    private void preparePlayableState(int bytes) throws Exception {
        TrackInfo t = TrackInfo.builder()
            .trackId("t1")
            .title("test")
            .filePath("/tmp/dummy.wav")
            .durationMs(60_000)
            .sampleRate(48_000)
            .channels(2)
            .build();
        setField("currentTrack", t);
        setField("audioLine", new DummySourceDataLine());
        prepareStreamOnly(bytes);
        setField("currentState", PlaybackStatus.State.STOPPED);
        setField("currentPositionMs", 0L);
    }

    private void prepareStreamOnly(int bytes) throws Exception {
        byte[] data = new byte[bytes];
        AudioFormat fmt = new AudioFormat(48000, 16, 2, true, false);
        AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), fmt, data.length / 4);
        setField("audioStream", ais);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = BasicLocalPlaybackEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(engine, value);
    }

    // Minimal fake line for state-machine testing (no real hardware)
    private static class DummySourceDataLine implements SourceDataLine {
        private final AudioFormat format = new AudioFormat(48000, 16, 2, true, false);
        private boolean open = true;

        public void open(AudioFormat format, int bufferSize) {}
        public void open(AudioFormat format) {}
        public int write(byte[] b, int off, int len) { return len; }
        public void start() {}
        public void stop() {}
        public boolean isRunning() { return true; }
        public boolean isActive() { return true; }
        public AudioFormat getFormat() { return format; }
        public int getBufferSize() { return 8192; }
        public int available() { return 4096; }
        public int getFramePosition() { return 0; }
        public long getLongFramePosition() { return 0; }
        public long getMicrosecondPosition() { return 0; }
        public float getLevel() { return 0; }
        public javax.sound.sampled.Line.Info getLineInfo() { return new DataLine.Info(SourceDataLine.class, format); }
        public void open() { open = true; }
        public void close() { open = false; }
        public boolean isOpen() { return open; }
        public Control[] getControls() { return new Control[0]; }
        public boolean isControlSupported(Control.Type control) { return false; }
        public Control getControl(Control.Type control) { throw new IllegalArgumentException(); }
        public void addLineListener(LineListener listener) {}
        public void removeLineListener(LineListener listener) {}
        public void drain() {}
        public void flush() {}
    }
}
