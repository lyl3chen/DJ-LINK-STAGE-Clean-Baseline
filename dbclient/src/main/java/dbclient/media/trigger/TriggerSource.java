package dbclient.media.trigger;

/**
 * 触发来源枚举
 */
public enum TriggerSource {
    CDJ,    // CDJ / beat-link 实时源
    LOCAL,  // 本地播放器源
    ANY     // 任意来源（用于条件过滤）
}
