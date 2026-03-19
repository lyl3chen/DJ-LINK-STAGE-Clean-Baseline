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

    // 上一帧状态（用于状态变化检测）
    private TriggerContext lastContext = null;

    public TriggerEngine() {}

    // ==================== 规则管理 ====================

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
     */
    private boolean matchesBeat(TriggerCondition cond, TriggerContext ctx) {
        Integer beatInterval = cond.getBeatInterval();
        if (beatInterval == null || beatInterval <= 0) return false;

        Integer beatNumber = ctx.getBeatNumber();
        if (beatNumber == null) return false;

        // 每 N 拍的第一拍触发（即 0, N, 2N...）
        return beatNumber % beatInterval == 0;
    }

    /**
     * MARKER 条件匹配：到达指定 Marker
     */
    private boolean matchesMarker(TriggerCondition cond, TriggerContext ctx) {
        String markerId = cond.getMarkerId();
        if (markerId == null) return false;

        List<dbclient.media.model.MarkerPoint> markers = ctx.getMarkers();
        if (markers == null || markers.isEmpty()) return false;

        long pos = ctx.getPositionMs();
        // 容差 50ms
        final long TOLERANCE = 50;

        for (dbclient.media.model.MarkerPoint m : markers) {
            if (markerId.equals(m.getId()) && m.isEnabled()) {
                long markerTime = m.getTimeMs();
                if (Math.abs(pos - markerTime) <= TOLERANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * POSITION 条件匹配：到达指定位置
     */
    private boolean matchesPosition(TriggerCondition cond, TriggerContext ctx) {
        Long targetPos = cond.getPositionMs();
        if (targetPos == null) return false;

        long pos = ctx.getPositionMs();
        // 容差 50ms
        final long TOLERANCE = 50;

        return Math.abs(pos - targetPos) <= TOLERANCE;
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
     */
    private void dispatchEvent(TriggerEvent event) {
        for (Consumer<TriggerEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                System.err.println("[TriggerEngine] Event listener error: " + e.getMessage());
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
