package com.argus.rag.ingestion.service.pipeline.transformer;

/**
 * 分块实现层级，对应 WeKnora {@code chunker.StrategyTier}。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public enum StrategyTier {
    /** 一级：Markdown 标题感知 */
    HEADING("heading"),
    /** 二级：启发式边界 */
    HEURISTIC("heuristic"),
    /** 三级：legacy 递归分割 */
    LEGACY("legacy");

    private final String code;

    StrategyTier(String code) {
        this.code = code;
    }

    /**
     * @return 层级标识（用于诊断/元数据标签）
     */
    public String code() {
        return code;
    }
}
