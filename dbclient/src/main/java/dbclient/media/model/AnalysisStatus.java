package dbclient.media.model;

/**
 * 分析状态枚举
 */
public enum AnalysisStatus {
    PENDING,      // 待分析
    ANALYZING,    // 分析中
    COMPLETED,    // 已完成
    FAILED        // 失败
}
