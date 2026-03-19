package dbclient.media.trigger;

import dbclient.media.model.*;
import dbclient.media.library.*;
import dbclient.media.player.*;
import dbclient.media.analysis.*;

/**
 * MA2 Trigger 输出验证测试
 * 
 * 测试目标：
 * - TriggerEngine 命中 BEAT 规则
 * - Dispatcher 分发
 * - Ma2TriggerActionDriver 执行
 * - 复用 SyncOutputManager.sendMa2TestCommand()
 * 
 * 规则：BEAT 每 4 拍触发， FIRE_MA2_EXEC
 */
public class Ma2TriggerOutputVerification {

    public static void main(String[] args) {
        System.out.println("=== MA2 Trigger 输出验证 ===\n");
        
        // 1. 创建 TriggerEngine
        TriggerEngine engine = new TriggerEngine();
        
        // 2. 创建 Dispatcher
        TriggerActionDispatcher dispatcher = new TriggerActionDispatcher();
        
        // 3. 注册日志驱动
        dispatcher.registerDriver(new LogTriggerActionDriver());
        
        // 4. 注册 MA2 驱动（使用反射获取 SyncOutputManager）
        // 注意：这里只是模拟，实际需要从 Main 获取 SyncOutputManager 实例
        // 在真实环境中，SyncOutputManager 由框架注入
        System.out.println("[Ma2TriggerOutputVerification] Note: SyncOutputManager should be injected from Main");
        
        // 5. 注册事件监听器
        engine.addEventListener(event -> {
            System.out.println("\n>>> [TriggerEvent] " + event.getMessage());
            boolean result = dispatcher.dispatch(event.getAction(), event);
            System.out.println("[TriggerEvent] Dispatch result: " + result);
        });
        
        // 6. 添加规则：BEAT 每 4 拍触发，FIRE_MA2_EXEC
        TriggerRule rule = TriggerRule.builder()
            .id("ma2-beat-4")
            .name("MA2 每4拍触发")
            .condition(TriggerCondition.builder()
                .type(TriggerCondition.ConditionType.BEAT)
                .source(TriggerSource.LOCAL)
                .beatInterval(4)
                .build())
            .action(TriggerAction.builder()
                .type(TriggerAction.ActionType.FIRE_MA2_EXEC)
                .protocol("MA2")
                .payload("Executor 1")  // 默认命令
                .description("触发 MA2 Executor 1")
                .build())
            .build();
        engine.addRule(rule);
        
        System.out.println("[Ma2TriggerOutputVerification] Rule created: " + rule.getName());
        System.out.println("[Ma2TriggerOutputVerification] Rule condition: BEAT every 4 beats");
        System.out.println("[Ma2TriggerOutputVerification] Rule action: FIRE_MA2_EXEC -> Executor 1");
        
        // 7. 创建 Local 上下文模拟播放
        System.out.println("\n=== 模拟播放 ===");
        
        // Beat 0
        System.out.println("\n[1] Play at Beat 0...");
        TriggerContext ctx = createContext(PlaybackStatus.State.PLAYING, 0, 120, 0);
        engine.onContextUpdate(ctx);
        
        // Beat 4 (应该触发)
        System.out.println("\n[2] Play at Beat 4 (should trigger)...");
        ctx = createContext(PlaybackStatus.State.PLAYING, 2000, 120, 4);
        engine.onContextUpdate(ctx);
        
        // Beat 8 (应该触发)
        System.out.println("\n[3] Play at Beat 8 (should trigger)...");
        ctx = createContext(PlaybackStatus.State.PLAYING, 4000, 120, 8);
        engine.onContextUpdate(ctx);
        
        // Beat 12 (应该触发)
        System.out.println("\n[4] Play at Beat 12 (should trigger)...");
        ctx = createContext(PlaybackStatus.State.PLAYING, 6000, 120, 12);
        engine.onContextUpdate(ctx);
        
        System.out.println("\n=== 验证完成 ===");
        System.out.println("预期结果：Beat 4, 8, 12 时触发 FIRE_MA2_EXEC");
        System.out.println("如果 SyncOutputManager 已注入，应该向 MA2 发送命令");
    }
    
    private static TriggerContext createContext(PlaybackStatus.State state, long posMs, int bpm, int beat) {
        return TriggerContext.builder()
            .source(TriggerSource.LOCAL)
            .playbackState(state)
            .positionMs(posMs)
            .bpm(bpm)
            .beatNumber(beat)
            .phase(0.5)
            .trackId("test-track")
            .title("Test Track")
            .durationMs(180000)
            .beatGrid(BeatGrid.builder()
                .bpm(bpm)
                .durationMs(180000)
                .beatsPerMeasure(4)
                .firstBeatMs(0)
                .build())
            .build();
    }
}
