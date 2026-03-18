package dbclient.sync.timecode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class TimecodeCoreStateBehaviorTest {

    private TimecodeCore core;

    @Before
    public void setUp() {
        core = new TimecodeCore();
        core.start();
    }

    @After
    public void tearDown() {
        core.stop();
    }

    @Test
    public void playingShouldIncrementFrames() throws Exception {
        core.update(state(1, "PLAYING", true, 1000, "trackA"));
        long f1 = core.getCurrentFrame();
        Thread.sleep(140);
        long f2 = core.getCurrentFrame();
        assertTrue("frame should increase in PLAYING", f2 > f1);
    }

    @Test
    public void pausedShouldHoldFrame() throws Exception {
        core.update(state(1, "PLAYING", true, 2000, "trackA"));
        Thread.sleep(100);
        core.update(state(1, "PAUSED", false, 2400, "trackA"));

        long f1 = core.getCurrentFrame();
        Thread.sleep(160);
        long f2 = core.getCurrentFrame();
        assertEquals("frame should hold in PAUSED", f1, f2);
    }

    @Test
    public void stoppedShouldResetToZero() {
        core.update(state(1, "PLAYING", true, 3000, "trackA"));
        core.update(state(1, "STOPPED", false, 0, ""));
        assertEquals(0, core.getCurrentFrame());
    }

    @Test
    public void jumpSeekShouldReanchorImmediately() throws Exception {
        // Start near 1s => ~25f
        core.update(state(1, "PLAYING", true, 1000, "trackA"));
        Thread.sleep(80);
        long before = core.getCurrentFrame();

        // Simulate jump/hot-cue: same track, currentTimeMs jumps to 10s
        core.update(state(1, "PLAYING", true, 10_000, "trackA"));
        long after = core.getCurrentFrame();

        // should re-anchor near 250f (not continue from previous small frame)
        assertTrue("after jump should be re-anchored high", after >= 240);
        assertTrue("after jump should be greater than before", after > before);
    }

    /**
     * hot cue / 跳点在当前系统对应：
     * - 播放中 currentTimeMs 发生大跳变（JUMP_THRESHOLD 以上）
     * - 或 trackId 改变引发重锚
     */
    private Map<String, Object> state(int playerNum, String st, boolean playing, long currentTimeMs, String trackId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("number", playerNum);
        p.put("state", st);
        p.put("playing", playing);
        p.put("currentTimeMs", currentTimeMs);
        p.put("trackId", trackId);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sourcePlayer", playerNum);
        root.put("players", Collections.singletonList(p));
        root.put("sourceState", st);
        return root;
    }
}
