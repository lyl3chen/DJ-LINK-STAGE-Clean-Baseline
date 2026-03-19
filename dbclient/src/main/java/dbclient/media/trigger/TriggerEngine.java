package dbclient.media.trigger;

import dbclient.media.model.PlaybackStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 触发引擎核心
 * 
 * 职责：
 * - 管理触发规则（添加/删除/启用/禁用）
 * - 接收 TriggerContext 更新
 * - 评估规则匹配
 * - 生成 TriggerEvent 并派发
 * 
 * 约束：
 * - 不直接执行动作，只生成事件
 * - 动作执行由 TriggerActionDispatcher 负责
 * - 不依赖 AnalysisResult 内部细节，只消费 TriggerContext 标准字段
 * 
 * MVP 阶段：
 * - 只支持基础条件类型（BEAT / MARKER / POSITION / STATE_CHANGE）
 * - 不支持复杂组合条件
 */
public class TriggerEngine {

    private final List<TriggerRule> rules = new CopyOnWriteArrayList<>();
    private final List<Consumer<TriggerEvent>> eventListeners = new CopyOnWriteArrayList<>();

    // 上一帧状态（用于状态变化检测和防重复触发）
    private TriggerContext lastContext = null;
    
    // 防重复触发状态追踪
    // key: ruleId, value: 最后触发时的相关状态
    private final java.util.Map<String, TriggeredState> lastTriggeredState = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 记录规则已触发状态（用于防重复）
     */
    private static class TriggeredState {
        long lastTriggerTimestamp;
        Integer lastBeatNumber;      // BEAT: 记录上次触发的 beat
        String lastMarkerId;         // MARKER: 记录上次触发的 marker
        Long lastPositionMs;         // POSITION: 记录上次触发的位置
        PlaybackStatus.State lastState; // STATE_CHANGE: 记录上次触发时的状态

        TriggeredState() {
            this.lastTriggerTimestamp = 0;
        }
    }

    public TriggerEngine() {}

    // ==================== 规则管理 ====================

    /**
     * 当播放开始时调用（重新 Play 时）
     * 
     * 语义：
     * - Stop 后重新 Play 同一首歌：保留该曲目的触发状态（同一次播放周期）
     * - 切歌后 Play 新歌：调用 clearAllTriggerStates()
     * 
     * 也就是说，同一首歌的 Stop/Play 不重置，切换曲目才重置
     */
    public void onPlayStarted() {
        // MVP 阶段：同一首歌的 Stop/Play 不重置触发状态
        // 如果需要严格区分播放周期，可以在这里实现
    }

    /**
     * 清除所有触发状态（切歌时调用）
     */
    public void clearAllTriggerStates() {
        lastTriggeredState.clear();
        lastContext = null;
    }

    /**
     * 添加触发规则
     */
    public void addRule(TriggerRule rule) {
        if (rule != null && rule.getId() != null) {
            rules.add(rule);
        }
    }

    /**
     * 移除触发规则
     */
    public void removeRule(String ruleId) {
        rules.removeIf(r -> ruleId.equals(r.getId()));
    }

    /**
     * 获取所有规则
     */
    public List<TriggerRule> getRules() {
        return new ArrayList<>(rules);
    }

    /**
     * 启用/禁用规则
     */
    public void setRuleEnabled(String ruleId, boolean enabled) {
        for (TriggerRule rule : rules) {
            if (ruleId.equals(rule.getId())) {
                rule.setEnabled(enabled);
                rule.touch();
                break;
            }
        }
    }

    // ==================== 事件监听 ====================

    /**
     * 注册事件监听器
     */
    public void addEventListener(Consumer<TriggerEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    /**
     * 移除事件监听器
     */
    public void removeEventListener(Consumer<TriggerEvent> listener) {
        eventListeners.remove(listener);
    }

    // ==================== 核心评估 ====================

    /**
     * 处理上下文更新
     * 
     * 每当播放状态变化时调用此方法
     * 引擎会评估所有启用的规则并生成事件
     */
    public void onContextUpdate(TriggerContext context) {
        if (context == null) return;

        // 评估所有规则
        for (TriggerRule rule : rules) {
            if (!rule.isEnabled()) continue;
            if (rule.getCondition() == null || rule.getAction() == null) continue;

            if (matches(rule.getCondition(), context)) {
                // 生成事件
                TriggerEvent event = TriggerEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .ruleId(rule.getId())
                    .ruleName(rule.getName())
                    .source(context.getSource())
                    .action(rule.getAction())
                    .context(context)
                    .message(formatMessage(rule, context))
                    .build();

                // 派发事件
                dispatchEvent(event);
            }
        }

        // 更新上一帧状态
        lastContext = context;
    }

    /**
     * 评估条件是否匹配
     */
    private boolean matches(TriggerCondition cond, TriggerContext ctx) {
        if (cond == null || ctx == null) return false;

        // Source 过滤
        if (cond.getSource() != null && cond.getSource() != TriggerSource.ANY) {
            if (ctx.getSource() != cond.getSource()) {
                return false;
            }
        }

        switch (cond.getType()) {
            case BEAT:
                return matchesBeat(cond, ctx);
            case MARKER:
                return matchesMarker(cond, ctx);
            case POSITION:
                return matchesPosition(cond, ctx);
            case STATE_CHANGE:
                return matchesStateChange(cond, ctx);
            default:
                return false;
        }
    }

    /**
     * BEAT 条件匹配：每 N 拍触发
     * 防重复：只在 beatNumber 变化且命中 interval 时触发
     * 
     * 重置语义：
     * - seek 到更早位置后允许重新触发
     * - stop/play 后同一曲目的 beat 触发历史保留
     * - 切歌后一定清空（调用 clearAllTriggerStates）
     */
    private boolean matchesBeat(TriggerCondition cond, TriggerContext ctx) {
        Integer beatInterval = cond.getBeatInterval();
        if (beatInterval == null || beatInterval <= 0) return false;

        Integer beatNumber = ctx.getBeatNumber();
        if (beatNumber == null) return false;

        // 防重复：检查 beatNumber 是否变化
        TriggeredState state = lastTriggeredState.get(cond.getType() + "_" + cond.getBeatInterval());
        
        // 如果 beatNumber 比上次小（seek 回更早位置），允许重新触发
        if (state != null && state.lastBeatNumber != null && beatNumber < state.lastBeatNumber) {
            // seek 回更早位置，清除该规则的触发状态
            lastTriggeredState.remove(cond.getType() + "_" + cond.getBeatInterval());
            state = null;
        }
        
        if (state != null && state.lastBeatNumber != null && state.lastBeatNumber.equals(beatNumber)) {
            // 同一个 beat 上重复触发，跳过
            return false;
        }

        // 每 N 拍的第一拍触发（即 0, N, 2N...）
        return beatNumber % beatInterval == 0;
    }

    /**
     * MARKER 条件匹配：到达指定 Marker
     * 防重复：只在该 marker 首次命中时触发，同一 marker 不重复触发
     */
    private boolean matchesMarker(TriggerCondition cond, TriggerContext ctx) {
        String markerId = cond.getMarkerId();
        if (markerId == null) return false;

        List<dbclient.media.model.MarkerPoint> markers = ctx.getMarkers();
        if (markers == null || markers.isEmpty()) return false;

        long pos = ctx.getPositionMs();
        // 容差 50ms
        final long TOLERANCE = 50;

        // 防重复：检查该 marker 是否刚已被触发过
        TriggeredState state = lastTriggeredState.get("MARKER_" + markerId);
        if (state != null && markerId.equals(state.lastMarkerId)) {
            // 该 marker 刚已被触发过，跳过
            return false;
        }

        for (dbclient.media.model.MarkerPoint m : markers) {
            if (markerId.equals(m.getId()) && m.isEnabled()) {
                long markerTime = m.getTimeMs();
                if (Math.abs(pos - markerTime) <= TOLERANCE) {
                    return true;
                }
                // 检查是否需要重置：当 position < markerTime - TOLERANCE 时重置（即 seek 回 marker 前）
                if (pos < markerTime - TOLERANCE) {
                    lastTriggeredState.remove("MARKER_" + markerId);
                }
            }
        }
        return false;
    }

    /**
     * POSITION 条件匹配：到达指定位置
     * 防重复：只在首次跨越阈值时触发一次
     */
    private boolean matchesPosition(TriggerCondition cond, TriggerContext ctx) {
        Long targetPos = cond.getPositionMs();
        if (targetPos == null) return false;

        long pos = ctx.getPositionMs();
        // 容差 50ms
        final long TOLERANCE = 50;

        if (Math.abs(pos - targetPos) > TOLERANCE) {
            // 不在范围内，判断是否需要重置防重复状态
            // 当 position < targetPos - TOLERANCE 时重置（即 seek 回阈值前）
            if (pos < targetPos - TOLERANCE) {
                lastTriggeredState.remove("POSITION_" + targetPos);
            }
            return false;
        }

        // 防重复：检查该位置是否已触发过
        TriggeredState state = lastTriggeredState.get("POSITION_" + targetPos);
        if (state != null && state.lastPositionMs != null) {
            // 该位置已触发过，跳过
            return false;
        }

        return true;
    }

    /**
     * STATE_CHANGE 条件匹配：播放状态变化
     */
    private boolean matchesStateChange(TriggerCondition cond, TriggerContext ctx) {
        PlaybackStatus.State targetState = cond.getTargetState();
        if (targetState == null) return false;

        if (lastContext == null) {
            // 首次帧，如果当前是目标状态也算匹配
            return ctx.getPlaybackState() == targetState;
        }

        // 状态变化检测：上一帧不是目标状态，当前是目标状态
        return lastContext.getPlaybackState() != targetState 
            && ctx.getPlaybackState() == targetState;
    }

    /**
     * 派发事件到监听器
     * 同时记录防重复状态
     */
    private void dispatchEvent(TriggerEvent event) {
        // 记录触发状态（防重复）
        recordTriggeredState(event);

        for (Consumer<TriggerEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                System.err.println("[TriggerEngine] Event listener error: " + e.getMessage());
            }
        }
    }

    /**
     * 记录触发状态用于防重复
     * 
     * 重置语义：
     * - POSITION: 当 positionMs < targetPos - TOLERANCE 时重置（即 seek 回阈值前）
     * - MARKER: 当 positionMs < markerTime - TOLERANCE 时重置（即 seek 回 marker 前）
     * - 当 source 变化时（切歌）自动清空所有状态
     */
    private void recordTriggeredState(TriggerEvent event) {
        TriggerCondition cond = event.getContext().getPlaybackState() != null 
            ? null : null; // This is a placeholder, we need to get condition from rule
        
        // 从规则获取条件类型并记录状态
        for (TriggerRule rule : rules) {
            if (rule.getId().equals(event.getRuleId())) {
                TriggerCondition c = rule.getCondition();
                if (c == null) break;
                
                TriggeredState state = new TriggeredState();
                state.lastTriggerTimestamp = System.currentTimeMillis();

                switch (c.getType()) {
                    case BEAT:
                        state.lastBeatNumber = event.getContext().getBeatNumber();
                        lastTriggeredState.put(c.getType() + "_" + c.getBeatInterval(), state);
                        break;
                    case MARKER:
                        state.lastMarkerId = c.getMarkerId();
                        lastTriggeredState.put("MARKER_" + c.getMarkerId(), state);
                        break;
                    case POSITION:
                        state.lastPositionMs = c.getPositionMs();
                        lastTriggeredState.put("POSITION_" + c.getPositionMs(), state);
                        break;
                    case STATE_CHANGE:
                        state.lastState = event.getContext().getPlaybackState();
                        lastTriggeredState.put("STATE_" + c.getTargetState(), state);
                        break;
                }
                break;
            }
        }
    }

    /**
     * 格式化事件消息
     */
    private String formatMessage(TriggerRule rule, TriggerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Trigger: ").append(rule.getName());
        sb.append(" | Source: ").append(ctx.getSource());
        sb.append(" | Pos: ").append(ctx.getPositionMs()).append("ms");
        if (ctx.getBpm() != null) {
            sb.append(" | BPM: ").append(ctx.getBpm());
        }
        if (ctx.getBeatNumber() != null) {
            sb.append(" | Beat: ").append(ctx.getBeatNumber());
        }
        return sb.toString();
    }

    /**
     * 获取统计信息
     */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalRules", rules.size());
        stats.put("enabledRules", rules.stream().filter(TriggerRule::isEnabled).count());
        stats.put("listeners", eventListeners.size());
        return stats;
    }
}
