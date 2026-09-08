package com.argus.rag.ingestion.service.pipeline.transformer;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自适应三层策略链文档转换器，实现 Spring AI {@link DocumentTransformer}。
 *
 * <p>基于 {@link AdaptiveStrategySplitter} 将长文档按 Markdown 标题 / 启发式边界 /
 * legacy 递归三层策略拆分为语义完整的 token 预算分块。每个分块携带章节路径
 * （面包屑）、字符起止位置与策略标识等元数据。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
@Component
public class AdaptiveStrategyChunkTransformer implements DocumentTransformer {

    /** 分块策略标识，写入分块元数据 */
    private static final String STRATEGY = "adaptive-strategy-token-v1";

    private final ChunkingProperties properties;

    /**
     * @param properties 分块配置属性
     */
    public AdaptiveStrategyChunkTransformer(ChunkingProperties properties) {
        this.properties = properties;
    }

    /**
     * @param documents 原始文档列表
     * @return 拆分后的分块文档列表
     */
    @Override
    public List<Document> apply(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> chunks = new ArrayList<>();
        for (Document document : documents) {
            chunks.addAll(chunkDocument(document));
        }
        return chunks;
    }

    // 对单个文档运行自适应策略链并构建分块文档列表
    private List<Document> chunkDocument(Document document) {
        if (document == null || document.getText() == null || document.getText().isBlank()) {
            return List.of();
        }
        String text = document.getText();
        SplitterConfig cfg = config();
        List<Chunk> chunkList = AdaptiveStrategySplitter.split(text, cfg);

        List<Document> docs = new ArrayList<>(chunkList.size());
        int index = 0;
        for (Chunk c : chunkList) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (document.getMetadata() != null) {
                metadata.putAll(document.getMetadata());
            }
            String sectionPath = c.contextHeader() == null || c.contextHeader().isBlank()
                    ? ""
                    : c.contextHeader().replace("\n", " > ");
            metadata.put("sectionPath", sectionPath);
            metadata.put("charStart", c.start());
            metadata.put("charEnd", c.end());
            metadata.put("chunkStrategy", STRATEGY);
            String id = document.getId() == null ? null : document.getId() + ":" + index;
            Document doc = Document.builder()
                    .id(id)
                    .text(c.embeddingContent())
                    .metadata(metadata)
                    .build();
            docs.add(doc);
            index++;
        }
        return docs;
    }

    // 将 token 级配置映射为字符级 SplitterConfig（auto 策略由文档画像选型）
    private SplitterConfig config() {
        int targetTokens = Math.max(1, properties.getTargetTokens());
        int maxTokens = Math.max(targetTokens, properties.getMaxTokens());
        int overlapTokens = Math.max(0, properties.getOverlapTokens());

        int chunkSize = TokenEstimator.charsForTokenLimit(targetTokens, TokenEstimator.LANG_MIXED);
        if (chunkSize <= 0) {
            chunkSize = SplitterConfig.DEFAULT_CHUNK_SIZE;
        }
        int chunkOverlap = TokenEstimator.charsForTokenLimit(overlapTokens, TokenEstimator.LANG_MIXED);
        if (chunkOverlap <= 0) {
            chunkOverlap = SplitterConfig.DEFAULT_CHUNK_OVERLAP;
        }
        return new SplitterConfig(chunkSize, chunkOverlap, SplitterConfig.DEFAULT_SEPARATORS,
                SplitterConfig.STRATEGY_AUTO, maxTokens, List.of());
    }
}

