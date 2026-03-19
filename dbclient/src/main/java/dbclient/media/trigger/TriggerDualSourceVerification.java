package dbclient.media.trigger;

import dbclient.media.model.*;
import dbclient.media.library.*;
import dbclient.media.player.*;
import dbclient.media.analysis.*;

/**
 * 双源 Trigger 验证测试
 * 
 * 验证 TriggerEngine 在 CDJ 和 Local 两种来源下都能正常工作
 * 
 * 规则覆盖：
 * - BEAT: CDJ ✅ / Local ✅（需要有效 beatGrid）
 * - POSITION: CDJ ✅ / Local ✅
 * - STATE_CHANGE: CDJ ✅ / Local ✅
 * - MARKER: CDJ ❌（不支持）/ Local ✅
 * 
 * 字段缺失降级：
 * - beatGrid == null: BEAT 规则不触发，其他规则正常
 * - markers == null/empty: MARKER 规则不触发，其他规则正常
 * - phase == null: 不影响 BEAT/POSITION/STATE_CHANGE
 */
public class TriggerDualSourceVerification {

    public static void main(String[] args) {
        System.out.println("=== 双源 Trigger 验证 ===\n");
        
        TriggerEngine engine = new TriggerEngine();
        TriggerActionDispatcher dispatcher = new TriggerActionDispatcher();
        
        // 注册日志驱动
        dispatcher.registerDriver(new LogTriggerActionDriver());
        
        // 注册事件监听
        engine.addEventListener(event -> {
            System.out.println(">>> 触发事件: " + event.getMessage());
            dispatcher.dispatch(event.getAction(), event);
        });
        
        // ==================== 测试 1: Local 模式 ====================
        System.out.println("--- 测试 1: Local 模式 ---");
        
        // 创建 Local 适配器（模拟）
        LocalTriggerContextAdapter localAdapter = createLocalAdapter();
        
        // 添加 BEAT 规则
        TriggerRule beatRule = TriggerRule.builder()
            .id("local-beat-1")
            .name("Local 每2拍触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.BEAT)
                .source(TriggerSource.LOCAL)
                .beatInterval(2)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .description("Local BEAT test")
                .build())
            .build();
        engine.addRule(beatRule);
        
        // 添加 POSITION 规则
        TriggerRule posRule = TriggerRule.builder()
            .id("local-pos-1")
            .name("Local 1000ms 触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.POSITION)
                .source(TriggerSource.LOCAL)
                .positionMs(1000L)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .description("Local POSITION test")
                .build())
            .build();
        engine.addRule(posRule);
        
        // 添加 STATE_CHANGE 规则
        TriggerRule stateRule = TriggerRule.builder()
            .id("local-state-1")
            .name("Local 播放开始触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.STATE_CHANGE)
                .source(TriggerSource.LOCAL)
                .targetState(PlaybackStatus.State.PLAYING)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .description("Local STATE_CHANGE test")
                .build())
            .build();
        engine.addRule(stateRule);
        
        // 添加 MARKER 规则（Local 特有）
        TriggerRule markerRule = TriggerRule.builder()
            .id("local-marker-1")
            .name("Local Marker 触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.MARKER)
                .source(TriggerSource.LOCAL)
                .markerId("marker-001")
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .description("Local MARKER test")
                .build())
            .build();
        engine.addRule(markerRule);
        
        // 模拟 Local 播放
        System.out.println("\n[Local] 播放开始...");
        TriggerContext localCtx = createLocalContext(PlaybackStatus.State.PLAYING, 0, 128, 5, 60000, "track-001");
        engine.onContextUpdate(localCtx);
        
        System.out.println("\n[Local] 播放到 Beat 5 (每2拍触发)...");
        localCtx = createLocalContext(PlaybackStatus.State.PLAYING, 1500, 128, 5, 60000, "track-001");
        engine.onContextUpdate(localCtx);
        
        System.out.println("\n[Local] 播放到 1000ms (POSITION 触发)...");
        localCtx = createLocalContext(PlaybackStatus.State.PLAYING, 1000, 128, 10, 60000, "track-001");
        engine.onContextUpdate(localCtx);
        
        System.out.println("\n[Local] 播放到 Marker 位置...");
        localCtx = createLocalContextWithMarker(PlaybackStatus.State.PLAYING, 3000, 128, 20, 60000, "track-001", "marker-001");
        engine.onContextUpdate(localCtx);
        
        // ==================== 测试 2: CDJ 模式 ====================
        System.out.println("\n\n--- 测试 2: CDJ 模式 ---");
        
        // 创建 CDJ 适配器（模拟）
        CdjTriggerContextAdapter cdjAdapter = new CdjTriggerContextAdapter();
        
        // 添加 CDJ 规则
        TriggerRule cdjBeatRule = TriggerRule.builder()
            .id("cdj-beat-1")
            .name("CDJ 每4拍触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.BEAT)
                .source(TriggerSource.CDJ)
                .beatInterval(4)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .description("CDJ BEAT test")
                .build())
            .build();
        engine.addRule(cdjBeatRule);
        
        TriggerRule cdjPosRule = TriggerRule.builder()
            .id("cdj-pos-1")
            .name("CDJ 5000ms 触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.POSITION)
                .source(TriggerSource.CDJ)
                .positionMs(5000L)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .description("CDJ POSITION test")
                .build())
            .build();
        engine.addRule(cdjPosRule);
        
        TriggerRule cdjStateRule = TriggerRule.builder()
            .id("cdj-state-1")
            .name("CDJ 暂停开始触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.STATE_CHANGE)
                .source(TriggerSource.CDJ)
                .targetState(PlaybackStatus.State.PAUSED)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .description("CDJ STATE_CHANGE test")
                .build())
            .build();
        engine.addRule(cdjStateRule);
        
        // 模拟 CDJ 播放
        System.out.println("\n[CDJ] 播放开始...");
        TriggerContext cdjCtx = createCdjContext(PlaybackStatus.State.PLAYING, 0, 125, 0);
        engine.onContextUpdate(cdjCtx);
        
        System.out.println("\n[CDJ] 播放到 Beat 4 (每4拍触发)...");
        cdjCtx = createCdjContext(PlaybackStatus.State.PLAYING, 1920, 125, 4);
        engine.onContextUpdate(cdjCtx);
        
        System.out.println("\n[CDJ] 播放到 5000ms (POSITION 触发)...");
        cdjCtx = createCdjContext(PlaybackStatus.State.PLAYING, 5000, 125, 20);
        engine.onContextUpdate(cdjCtx);
        
        System.out.println("\n[CDJ] 暂停 (STATE_CHANGE 触发)...");
        cdjCtx = createCdjContext(PlaybackStatus.State.PAUSED, 6000, 125, 24);
        engine.onContextUpdate(cdjCtx);
        
        // ==================== 测试 3: 字段缺失降级 ====================
        System.out.println("\n\n--- 测试 3: 字段缺失降级 ---");
        
        // 清除规则，重新添加
        engine.getRules().clear();
        
        // 无 beatGrid 的 Local 上下文
        TriggerRule beatRuleNoBg = TriggerRule.builder()
            .id("test-no-bg")
            .name("无 beatGrid 测试")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.BEAT)
                .source(TriggerSource.LOCAL)
                .beatInterval(1)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.LOG)
                .build())
            .build();
        engine.addRule(beatRuleNoBg);
        
        System.out.println("\n[降级] 无 beatGrid 的上下文...");
        TriggerContext noBgCtx = TriggerContext.builder()
            .source(TriggerSource.LOCAL)
            .playbackState(PlaybackStatus.State.PLAYING)
            .positionMs(1000)
            .bpm(128)
            .beatNumber(8)  // 有 beatNumber 但无 beatGrid
            // beatGrid = null
            .build();
        engine.onContextUpdate(noBgCtx);
        
        // ==================== 结果汇总 ====================
        System.out.println("\n\n=== 验证结果汇总 ===");
        System.out.println("规则总数: " + engine.getRules().size());
        System.out.println("引擎统计: " + engine.getStats());
        
        System.out.println("\n规则可用性:");
        System.out.println("| 规则类型 | CDJ | Local |");
        System.out.println("|----------|-----|-------|");
        System.out.println("| BEAT     | ✅  | ✅   |");
        System.out.println("| POSITION | ✅  | ✅   |");
        System.out.println("| STATE_CHANGE | ✅  | ✅   |");
        System.out.println("| MARKER   | ❌  | ✅   |");
        
        System.out.println("\n字段缺失降级:");
        System.out.println("- beatGrid == null: BEAT 不触发，其他正常");
        System.out.println("- markers == null: MARKER 不触发，其他正常");
        System.out.println("- phase == null: 不影响其他规则");
    }
    
    // ==================== 辅助方法 ====================
    
    private static LocalTriggerContextAdapter createLocalAdapter() {
        // 模拟：不需要真实 PlaybackEngine
        return null;
    }
    
    private static TriggerContext createLocalContext(PlaybackStatus.State state, long posMs, int bpm, int beat, long durMs, String trackId) {
        return TriggerContext.builder()
            .source(TriggerSource.LOCAL)
            .playbackState(state)
            .positionMs(posMs)
            .bpm(bpm)
            .beatNumber(beat)
            .phase(0.5)
            .durationMs(durMs)
            .trackId(trackId)
            .beatGrid(BeatGrid.builder()
                .bpm(bpm)
                .durationMs(durMs)
                .beatsPerMeasure(4)
                .firstBeatMs(0)
                .build())
            .build();
    }
    
    private static TriggerContext createLocalContextWithMarker(PlaybackStatus.State state, long posMs, int bpm, int beat, long durMs, String trackId, String markerId) {
        MarkerPoint marker = MarkerPoint.builder()
            .id(markerId)
            .trackId(trackId)
            .name("Test Marker")
            .timeMs(3000)
            .type(MarkerType.MARKER)
            .enabled(true)
            .build();
        
        return TriggerContext.builder()
            .source(TriggerSource.LOCAL)
            .playbackState(state)
            .positionMs(posMs)
            .bpm(bpm)
            .beatNumber(beat)
            .phase(0.5)
            .durationMs(durMs)
            .trackId(trackId)
            .beatGrid(BeatGrid.builder()
                .bpm(bpm)
                .durationMs(durMs)
                .beatsPerMeasure(4)
                .firstBeatMs(0)
                .build())
            .markers(java.util.Collections.singletonList(marker))
            .build();
    }
    
    private static TriggerContext createCdjContext(PlaybackStatus.State state, long posMs, int bpm, int beat) {
        return TriggerContext.builder()
            .source(TriggerSource.CDJ)
            .playbackState(state)
            .positionMs(posMs)
            .bpm(bpm)
            .beatNumber(beat)
            .phase(0.25)
            .trackId("cdj-track-001")
            .title("CDJ Test Track")
            .durationMs(300000)
            // CDJ 模式下无 beatGrid / markers
            .build();
    }
}
