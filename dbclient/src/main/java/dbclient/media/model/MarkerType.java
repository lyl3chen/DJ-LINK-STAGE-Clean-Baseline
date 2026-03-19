package dbclient.media.model;

/**
 * Marker 类型枚举
 * 
 * 扩展说明：
 * - MARKER: 通用标记
 * - CUE: Cue 点（DJ 常用）
 * - TRIGGER: 触发点（用于 Trigger Engine）
 * - SECTION_START / SECTION_END: 段落边界
 * - DROP / BUILD / BREAK: 能量标记
 * - CUSTOM: 自定义类型
 */
public enum MarkerType {
    MARKER,           // 通用标记
    CUE,              // Cue 点
    TRIGGER,          // 触发点
    SECTION_START,    // 段落开始
    SECTION_END,      // 段落结束
    DROP,             // Drop（能量高点）
    BUILD,            // Build（能量上升）
    BREAK,            // Break（能量停顿）
    CUSTOM            // 自定义
}
