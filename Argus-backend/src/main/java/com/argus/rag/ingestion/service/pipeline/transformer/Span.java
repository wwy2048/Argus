package com.argus.rag.ingestion.service.pipeline.transformer;

/**
 * 受保护文本区间，对应 WeKnora {@code chunker.span}。
 *
 * <p>{@code start}/{@code end} 统一定义为源文本的 UTF-16 字符索引
 * （与 {@link String} 切片一致），不再做码点（rune）换算。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
record Span(int start, int end) {

    /**
     * @return 区间长度（字符数）
     */
    int len() {
        return end - start;
    }
}
