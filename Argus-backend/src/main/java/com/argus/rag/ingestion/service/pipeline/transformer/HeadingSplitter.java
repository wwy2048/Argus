package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一级分割器：Markdown 标题感知分块，对应 WeKnora {@code chunker.heading_splitter.go}。
 *
 * <p>具有完整标题结构的文档按标题边界拆分，并给每个分块前置当前活动标题的
 * 面包屑（如 "# 第一章\n## 1.2 小节"）。当文档缺少可用标题结构或标题拆分
 * 仅产生单个区间时，回退到 legacy 分割器。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
final class HeadingSplitter {

    private HeadingSplitter() {
    }

    /** 区间起点（UTF-16 字符偏移）及其原始标题行 */
    private record HeadingBoundary(int start, String line) {
    }

    /** 章节内某处的字符偏移与该处生效的面包屑 */
    private record SectionBreadcrumb(int start, String breadcrumb) {
    }

    /**
     * Tier-1 实现。profile 可为 null；当策略解析器已运行文档画像（auto 策略）时，
     * 复用同一 profile 以避免重复扫描整篇文档。
     *
     * @param text    源文本
     * @param cfg     分块配置
     * @param profile 文档画像（可空）
     * @return 分块列表
     */
    static List<Chunk> splitByHeadings(String text, SplitterConfig cfg, DocProfile profile) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (profile == null) {
            profile = DocProfile.profile(text);
        }
        int primaryLevel = profile.dominantHeadingLevel();
        if (primaryLevel == 0) {
            return LegacyTextSplitter.split(text, cfg);
        }

        List<HeadingBoundary> bounds = findHeadingBoundaries(text, primaryLevel);
        if (bounds.size() <= 1) {
            return LegacyTextSplitter.split(text, cfg);
        }

        HeadingHierarchy hierarchy = HeadingHierarchy.empty();

        List<Chunk> out = new ArrayList<>();
        int seq = 0;

        for (int i = 0; i < bounds.size(); i++) {
            HeadingBoundary b = bounds.get(i);
            int end = text.length();
            if (i + 1 < bounds.size()) {
                end = bounds.get(i + 1).start();
            }
            if (b.line() != null && !b.line().isEmpty()) {
                hierarchy.observe(b.line());
            }
            String breadcrumb = hierarchy.breadcrumbWithHashes();
            HeadingHierarchy sectionStart = hierarchy.copy();
            observeSubHeadings(text, b.start(), end, primaryLevel, hierarchy);

            String sectionContent = text.substring(b.start(), end);
            int secLen = sectionContent.length();
            if (secLen == 0) {
                continue;
            }

            int bcLen = breadcrumb.length();
            // 单块区间直接输出；面包屑单独跟踪（ContextHeader），保持 End-Start == len(Content)。
            if (bcLen + 2 + secLen <= cfg.chunkSize()) {
                out.add(new Chunk(sectionContent, breadcrumb, seq, b.start(), end));
                seq++;
                continue;
            }

            // 区间过大：委托给 legacy 分割器做内部细分。每个子块携带其起始处最深标题的面包屑。
            List<SectionBreadcrumb> subBreadcrumbs = sectionBreadcrumbs(sectionContent, primaryLevel, sectionStart);
            List<Chunk> subChunks = LegacyTextSplitter.split(sectionContent, cfg);
            for (Chunk sub : subChunks) {
                out.add(new Chunk(
                        sub.content(),
                        breadcrumbAtOffset(subBreadcrumbs, sub.start(), breadcrumb),
                        seq,
                        b.start() + sub.start(),
                        b.start() + sub.end()));
                seq++;
            }
        }

        return coalesceTinyChunks(out, cfg.chunkSize());
    }

    /**
     * 找出文档中位于围栏代码块之外、层级 &le; primaryLevel 的 Markdown 标题边界。
     * 返回的边界首元素必在偏移 0 处（覆盖首个标题之前的引言）。
     *
     * @param text         源文本
     * @param primaryLevel 主导标题层级
     * @return 标题边界列表
     */
    static List<HeadingBoundary> findHeadingBoundaries(String text, int primaryLevel) {
        List<HeadingBoundary> bounds = new ArrayList<>();
        bounds.add(new HeadingBoundary(0, ""));
        if (text.isEmpty()) {
            return bounds;
        }

        int pos = 0;
        boolean inFence = false;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                pos += line.length();
                if (i < lines.length - 1) {
                    pos++;
                }
                continue;
            }
            if (!inFence) {
                MarkdownPatterns.Heading h = MarkdownPatterns.matchHeading(line);
                if (h != null) {
                    int level = h.level();
                    if (level >= 1 && level <= primaryLevel && pos > 0) {
                        bounds.add(new HeadingBoundary(pos, line));
                    }
                    if (level >= 1 && level <= primaryLevel && pos == 0) {
                        // 首行即标题 —— 更新前导边界。
                        bounds.set(0, new HeadingBoundary(0, line));
                    }
                }
            }
            pos += line.length();
            if (i < lines.length - 1) {
                pos++;
            }
        }
        return bounds;
    }

    /**
     * 遍历区间内的行，把层级 &gt; primaryLevel 的更深标题喂给层级，保持面包屑状态正确。
     */
    private static void observeSubHeadings(String source, int start, int end, int primaryLevel, HeadingHierarchy h) {
        if (end <= start) {
            return;
        }
        String sectionText = source.substring(start, end);
        boolean inFence = false;
        for (String line : sectionText.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            MarkdownPatterns.Heading heading = MarkdownPatterns.matchHeading(line);
            if (heading == null) {
                continue;
            }
            if (heading.level() > primaryLevel) {
                h.observe(line);
            }
        }
    }

    /**
     * 收集区间内部并记录各深层次标题（层级 &gt; primaryLevel）生效的面包屑。
     * seed 为区间起始时的层级状态；返回列表按 start 升序且首元素为 offset 0 的种子面包屑。
     */
    private static List<SectionBreadcrumb> sectionBreadcrumbs(String sectionText, int primaryLevel, HeadingHierarchy seed) {
        HeadingHierarchy h = seed;
        List<SectionBreadcrumb> result = new ArrayList<>();
        result.add(new SectionBreadcrumb(0, h.breadcrumbWithHashes()));
        int pos = 0;
        boolean inFence = false;
        String[] lines = sectionText.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                pos += line.length();
                if (i < lines.length - 1) {
                    pos++;
                }
                continue;
            }
            if (!inFence) {
                MarkdownPatterns.Heading heading = MarkdownPatterns.matchHeading(line);
                if (heading != null && heading.level() > primaryLevel) {
                    h.observe(line);
                    result.add(new SectionBreadcrumb(pos, h.breadcrumbWithHashes()));
                }
            }
            pos += line.length();
            if (i < lines.length - 1) {
                pos++;
            }
        }
        return result;
    }

    /**
     * 返回在给定字符偏移处生效的面包屑 —— 最后一个 start &le; offset 的条目。
     */
    private static String breadcrumbAtOffset(List<SectionBreadcrumb> bcs, int offset, String fallback) {
        String bc = fallback;
        for (SectionBreadcrumb e : bcs) {
            if (e.start() > offset) {
                break;
            }
            bc = e.breadcrumb();
        }
        return bc;
    }

    /**
     * 合并相邻的小分块，使其共享标题上下文，避免触发校验器 "too many tiny chunks" 规则。
     *
     * <p>仅当 {@code cur.End == next.Start}（保持位置不变式）且合并后不超出大小预算时合并；
     * 合并至约 {@code chunkSize/2} 即停止，避免过度打包。</p>
     */
    private static List<Chunk> coalesceTinyChunks(List<Chunk> in, int chunkSize) {
        if (in.size() <= 1 || chunkSize <= 0) {
            return in;
        }
        int target = chunkSize / 2;
        if (target < 200) {
            target = 200;
        }

        List<Chunk> out = new ArrayList<>(in.size());
        Chunk cur = in.get(0);
        int curLen = cur.contentLength();

        for (int i = 1; i < in.size(); i++) {
            Chunk next = in.get(i);
            int nextLen = next.contentLength();
            String sharedHeader = commonHeadingPrefix(cur.contextHeader(), next.contextHeader());
            if (!sharedHeader.isEmpty() && cur.end() == next.start() && curLen < target
                    && curLen + nextLen <= chunkSize) {
                cur = new Chunk(cur.content() + next.content(), sharedHeader, cur.seq(), cur.start(), next.end());
                curLen += nextLen;
                continue;
            }
            out.add(cur);
            cur = next;
            curLen = nextLen;
        }
        out.add(cur);

        // 重新编号 —— 下游期望 Seq 为稠密的 0..N-1 区间。
        for (int i = 0; i < out.size(); i++) {
            Chunk c = out.get(i);
            out.set(i, new Chunk(c.content(), c.contextHeader(), i, c.start(), c.end()));
        }
        return out;
    }

    /**
     * 两个面包屑字符串的最长按行对齐公共前缀。标题层级按行输出，逐行比较即可。
     */
    private static String commonHeadingPrefix(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        String[] la = a.split("\n", -1);
        String[] lb = b.split("\n", -1);
        int n = la.length;
        if (lb.length < n) {
            n = lb.length;
        }
        int common = 0;
        for (int i = 0; i < n; i++) {
            if (!la[i].equals(lb[i])) {
                break;
            }
            common = i + 1;
        }
        if (common == 0) {
            return "";
        }
        return String.join("\n", Arrays.copyOf(la, common));
    }
}
