package com.argus.rag.ingestion.service.pipeline.transformer;

/**
 * 分块过程中的一个最小文本单元，对应 WeKnora {@code chunker.splitUnit}。
 *
 * <p>{@code start}/{@code end} 为源文档中的 UTF-16 字符偏移；单元文本与源切片严格对应，
 * 但 {@link LegacyTextSplitter} 的合并逻辑可能插入零宽度的合成表头单元
 * （此时 {@code end - start == 0 != text.length()}），用于承载表格上下文。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
record SplitUnit(String text, int start, int end) {

    /**
     * @return 单元文本的字符数（UTF-16 code unit 长度）
     */
    int len() {
        return text == null || text.isEmpty() ? 0 : text.length();
    }
}
