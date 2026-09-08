package com.argus.rag.ingestion.service.pipeline.transformer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 冒烟测试：用仓库根 docs/ 下的真实 Markdown 文档《三层策略链md分块.md》
 * 驱动 {@link AdaptiveStrategyChunkTransformer}，打印分块结果便于人工核对效果。
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
class AdaptiveStrategyChunkTransformerTest {

    /** 测试素材：位于仓库根 docs/ 的说明文档 */
    private static final String MARKDOWN_FILE = "三层策略链md分块.md";

    @Test
    void chunkRealDesignDocAndPrint() throws IOException {
        String mdText = readMarkdownFile();
        AdaptiveStrategyChunkTransformer transformer =
                new AdaptiveStrategyChunkTransformer(new ChunkingProperties());

        List<Document> chunks = transformer.apply(List.of(
                Document.builder().id("design-doc").text(mdText).build()));

        System.out.printf("%n===== 源文档 %d 字符，分块 %d 个 =====%n", mdText.length(), chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document doc = chunks.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String preview = doc.getText().replace('\n', ' ').strip();
            if (preview.length() > 50) {
                preview = preview.substring(0, 50) + "…";
            }
            System.out.printf("[%02d] %s%n", i, preview);
        }
        System.out.println();
    }

    /** 从仓库根 docs/ 读取测试文档（容错：从工作目录逐级向上查找）。 */
    private static String readMarkdownFile() throws IOException {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve(MARKDOWN_FILE);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            if (dir.getParent() == null) {
                break;
            }
        }
        throw new IOException("未找到测试文档 docs/" + MARKDOWN_FILE + "（工作目录：" + cwd + "）");
    }
}
