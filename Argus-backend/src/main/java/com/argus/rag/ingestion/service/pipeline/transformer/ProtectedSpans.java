package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受保护文本区间检测，对应 WeKnora {@code chunker.protectedSpans}。
 *
 * <p>包含不应被分割的原子内容：LaTeX 块级公式、Markdown 图片/链接、表格、
 * 围栏代码块、内联代码。返回的区间为 UTF-16 字符索引，与 {@link String}
 * 切片语义一致，可直接用于 {@code substring}。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
final class ProtectedSpans {

    private ProtectedSpans() {
    }

    /** 受保护模式集合，按优先顺序匹配 */
    private static final Pattern[] PATTERNS = {
            // LaTeX block math
            Pattern.compile("(?s)\\$\\$.*?\\$\\$"),
            // Markdown images
            Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)"),
            // Markdown links
            Pattern.compile("\\[[^\\]]*\\]\\([^)]+\\)"),
            // Table header + separator
            Pattern.compile("(?m)[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|[\\r\\n]+"),
            // Table rows
            Pattern.compile("(?m)[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+"),
            // Fenced code blocks
            Pattern.compile("(?s)```(?:\\w+)?[\\r\\n].*?```"),
            // Markdown inline code
            Pattern.compile("`[^`\\r\\n]+`"),
    };

    /**
     * 找出所有非重叠的受保护区间，区间为 UTF-16 字符索引。
     *
     * @param text 源文本
     * @return 按起始位置升序、同位置按长度降序排列的区间
     */
    static List<Span> find(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<int[]> all = new ArrayList<>();
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                if (matcher.end() - matcher.start() > 0) {
                    all.add(new int[] { matcher.start(), matcher.end() });
                }
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }

        // Sort by start asc, then by length descending.
        all.sort((a, b) -> {
            if (a[0] != b[0]) {
                return Integer.compare(a[0], b[0]);
            }
            return Integer.compare(b[1] - b[0], a[1] - a[0]);
        });

        // Remove overlaps.
        List<Span> result = new ArrayList<>();
        int lastEnd = 0;
        for (int[] m : all) {
            if (m[0] >= lastEnd) {
                result.add(new Span(m[0], m[1]));
                lastEnd = m[1];
            }
        }
        return result;
    }
}
