package com.argus.rag.ingestion.service.pipeline.transformer;

/**
 * 追踪 Markdown 标题嵌套层级，用于为标题感知分割器生成面包屑
 * （如 "# 第一章" 下的 "## 1.2 小节"）。对应 WeKnora {@code chunker.heading_hierarchy.go}。
 *
 * <p>通过 level 栈建模：压入 N 级标题会弹出所有 level &gt;= N 的条目，
 * 因为前一个同级/后代标题已不再处于作用域。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public final class HeadingHierarchy {

    /** stack[i] 存放 level i+1 的标题文本 */
    private final String[] stack = new String[6];
    private int depth;

    /**
     * @return 空层级对象
     */
    public static HeadingHierarchy empty() {
        return new HeadingHierarchy();
    }

    /**
     * 在层级中观察一行：若为 Markdown 标题则更新栈。
     *
     * @param line 单行文本
     * @return 识别到的层级与标题文本，否则 {@code null}
     */
    public MarkdownPatterns.Heading observe(String line) {
        MarkdownPatterns.Heading h = MarkdownPatterns.matchHeading(line);
        if (h == null) {
            return null;
        }
        int level = h.level();
        if (level < 1 || level > 6) {
            return null;
        }
        stack[level - 1] = h.text();
        for (int i = level; i < 6; i++) {
            stack[i] = "";
        }
        if (level > depth) {
            depth = level;
        } else {
            depth = 0;
            for (int i = 0; i < 6; i++) {
                if (!stack[i].isEmpty()) {
                    depth = i + 1;
                }
            }
        }
        return h;
    }

    /**
     * @return 当前标题路径，以 " &gt; " 连接
     */
    public String breadcrumb() {
        if (depth == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (stack[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(stack[i]);
        }
        return sb.toString();
    }

    /**
     * @return 保留原始 # 前缀的路径，形如 "# 第一章\n## 2 小节"
     */
    public String breadcrumbWithHashes() {
        if (depth == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (stack[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("#".repeat(i + 1)).append(' ').append(stack[i]);
        }
        return sb.toString();
    }

    /**
     * @return 当前最深活动标题层级
     */
    public int depth() {
        return depth;
    }

    /**
     * @return 层级对象的深拷贝（供面包屑快照使用）
     */
    public HeadingHierarchy copy() {
        HeadingHierarchy copy = new HeadingHierarchy();
        System.arraycopy(stack, 0, copy.stack, 0, stack.length);
        copy.depth = depth;
        return copy;
    }
}
