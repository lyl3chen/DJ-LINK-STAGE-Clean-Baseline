package dbclient.input;

import dbclient.media.model.TrackInfo;

/**
 * 本地播放器输入源适配器（骨架实现）
 * 后续接入 LocalPlayer + LocalLibrary
 */
public class LocalSourceInput implements SourceInput {

    // TODO: 后续注入
    // private final LocalPlayer player;
    // private final LocalLibraryService library;

    private volatile String currentTrackId;
    private volatile double currentPositionMs;
    private volatile String currentState = "STOPPED";
    private volatile double currentBpm = 128.0;
    private volatile double currentPitch = 1.0;

    public LocalSourceInput() {
        // TODO: 构造函数注入 LocalPlayer 和 LocalLibraryService
    }

    @Override
    public String getType() {
        return "local";
    }

    @Override
    public String getDisplayName() {
        return "Local Player";
    }

    @Override
    public String getState() {
        // TODO: 从 LocalPlayer 获取真实状态
        return currentState;
    }

    @Override
    public boolean isOnline() {
        return true; // 本地播放器始终在线
    }

    @Override
    public double getSourceTimeSec() {
        // TODO: 从 LocalPlayer 获取真实时间
        return currentPositionMs / 1000.0;
    }

    @Override
    public double getSourceFrameRate() {
        return 25.0;
    }

    @Override
    public double getSourceBpm() {
        // TODO: 从 LocalPlayer 获取真实 BPM
        return currentBpm;
    }

    @Override
    public double getSourcePitch() {
        // TODO: 从 LocalPlayer 获取真实 pitch
        return currentPitch;
    }

    @Override
    public TrackInfo getCurrentTrack() {
        // TODO: 从 LocalLibraryService 查询当前曲目
        if (currentTrackId == null) {
            return null;
        }
        // stub 返回
        return TrackInfo.builder()
            .trackId(currentTrackId)
            .title("Stub Track")
            .artist("Stub Artist")
            .durationMs(300000)
            .build();
    }

    @Override
    public int getSourcePlayerNumber() {
        return 1; // 本地播放器固定为 1
    }

    // ====== Stub 控制方法（后续由 LocalPlayer 实现）======

    public void stubSetState(String state) {
        this.currentState = state;
    }

    public void stubSetPositionMs(double positionMs) {
        this.currentPositionMs = positionMs;
    }

    public void stubSetTrackId(String trackId) {
        this.currentTrackId = trackId;
    }

    public void stubSetBpm(double bpm) {
        this.currentBpm = bpm;
    }
}
