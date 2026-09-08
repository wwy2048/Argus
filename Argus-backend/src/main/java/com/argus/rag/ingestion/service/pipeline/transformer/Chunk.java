package com.argus.rag.ingestion.service.pipeline.transformer;

/**
 * 单个分块结果，对应 WeKnora {@code chunker.Chunk}。
 *
 * <p>{@code content} 为源文档 {@code [start, end)} 的精确切片；{@code contextHeader}
 * 是独立跟踪的上下文前缀（如 Markdown 标题面包屑），在嵌入/召回时另行拼接，
 * 不占用 {@code content} 的字符预算，从而保持 {@code end - start == content 长度}
 * 的位置不变式。</p>
 *
 * <p>{@code start}/{@code end} 均为源文档的 UTF-16 字符索引（与 {@link String}
 * 切片一致），可直接用 {@code text.substring(start, end)} 还原内容。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public record Chunk(String content, String contextHeader, int seq, int start, int end) {

    public Chunk(String content, int seq, int start, int end) {
        this(content, "", seq, start, end);
    }

    /**
     * @return 交给嵌入模型的文本：上下文头（若存在）+ 去首尾空白的内容
     */
    public String embeddingContent() {
        String body = content == null ? "" : content.strip();
        if (contextHeader == null || contextHeader.isBlank()) {
            return body;
        }
        return contextHeader + "\n\n" + body;
    }

    /**
     * @return 内容的字符数（UTF-16 code unit 长度）
     */
    public int contentLength() {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.length();
    }
}
