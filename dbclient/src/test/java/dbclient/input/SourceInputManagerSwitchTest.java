package dbclient.input;

import dbclient.media.model.TrackInfo;
import org.junit.Test;

import static org.junit.Assert.*;

public class SourceInputManagerSwitchTest {

    @Test
    public void shouldSwitchDjlinkToLocalAndBack() {
        SourceInputManager mgr = new SourceInputManager();
        SourceInput dj = new FakeSource("djlink", "STOPPED", 2);
        SourceInput local = new FakeSource("local", "STOPPED", 1);

        mgr.registerSource("djlink", dj);
        mgr.registerSource("local", local);

        // default active = first registered
        assertEquals("djlink", mgr.getActiveSourceName());
        assertEquals("djlink", mgr.getCurrentState().get("sourceType"));

        assertTrue(mgr.switchToSource("local"));
        assertEquals("local", mgr.getActiveSourceName());
        assertEquals("local", mgr.getCurrentState().get("sourceType"));

        assertTrue(mgr.switchToSource("djlink"));
        assertEquals("djlink", mgr.getActiveSourceName());
        assertEquals("djlink", mgr.getCurrentState().get("sourceType"));
    }

    private static class FakeSource implements SourceInput {
        private final String type;
        private final String state;
        private final int player;

        FakeSource(String type, String state, int player) {
            this.type = type;
            this.state = state;
            this.player = player;
        }

        public String getType() { return type; }
        public String getDisplayName() { return type; }
        public String getState() { return state; }
        public boolean isOnline() { return true; }
        public double getSourceTimeSec() { return 0; }
        public double getSourceFrameRate() { return 25.0; }
        public double getSourceBpm() { return 128.0; }
        public double getSourcePitch() { return 1.0; }
        public TrackInfo getCurrentTrack() { return null; }
        public int getSourcePlayerNumber() { return player; }
    }
}
