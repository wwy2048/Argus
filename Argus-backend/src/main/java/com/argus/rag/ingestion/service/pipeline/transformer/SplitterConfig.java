package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.List;

/**
 * 分块器配置，对应 WeKnora {@code chunker.SplitterConfig}。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public record SplitterConfig(
        int chunkSize,
        int chunkOverlap,
        List<String> separators,
        String strategy,
        int tokenLimit,
        List<String> languages) {

    /** 策略：自动选择层级 */
    public static final String STRATEGY_AUTO = "auto";
    /** 策略：标题感知 */
    public static final String STRATEGY_HEADING = "heading";
    /** 策略：启发式边界 */
    public static final String STRATEGY_HEURISTIC = "heuristic";
    /** 策略：递归（legacy 别名） */
    public static final String STRATEGY_RECURSIVE = "recursive";
    /** 策略：legacy */
    public static final String STRATEGY_LEGACY = "legacy";

    /** 默认分块大小（字符） */
    public static final int DEFAULT_CHUNK_SIZE = 512;
    /** 默认重叠（字符） */
    public static final int DEFAULT_CHUNK_OVERLAP = 80;
    /** 默认分隔符优先级 */
    public static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", "。");

    /**
     * 紧凑构造器：规范化空值与默认值。
     */
    public SplitterConfig {
        separators = separators == null ? DEFAULT_SEPARATORS : List.copyOf(separators);
        languages = languages == null ? List.of() : List.copyOf(languages);
        if (strategy == null || strategy.isBlank()) {
            strategy = STRATEGY_LEGACY;
        }
    }

    /**
     * @return 与 WeKnora {@code DefaultConfig()} 一致的默认配置
     */
    public static SplitterConfig defaults() {
        return new SplitterConfig(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, DEFAULT_SEPARATORS,
                STRATEGY_AUTO, 0, List.of());
    }
}
