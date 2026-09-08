package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 二级分割器：边界驱动的启发式分块，对应 WeKnora {@code chunker.heuristic_splitter.go}。
 *
 * <p>适用于缺少 Markdown 标题但含可识别结构线索（分页符、编号章节、多语言章节标记、
 * 视觉分隔线、全大写章节标题、页脚）的文档。算法找出全部候选边界，再做贪心装箱，
 * 把边界之间的块累积为分块直到下一块将超出 {@code chunkSize}；大于 {@code chunkSize}
 * 的块递归交由 legacy 分割器做内部分割。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
final class HeuristicSplitter {

    private HeuristicSplitter() {
    }

    /** 候选分块起点：字符偏移与优先级 */
    private record Boundary(int start, int priority) {
    }

    /**
     * Tier-2 实现。找不到启发式边界时回退到 legacy 分割器。
     *
     * @param text    源文本
     * @param cfg     分块配置
     * @param profile 文档画像（本层不直接使用，仅保持签名统一）
     * @return 分块列表
     */
    static List<Chunk> splitByHeuristics(String text, SplitterConfig cfg, DocProfile profile) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int totalChars = text.length();
        if (totalChars <= cfg.chunkSize()) {
            return LegacyTextSplitter.split(text, cfg);
        }

        List<Boundary> bounds = findHeuristicBoundaries(text, cfg.languages());
        // 剔除落在受保护区间（表格、代码块、LaTeX 块等）内部的边界。
        List<Span> charSpans = ProtectedSpans.find(text);
        if (!charSpans.isEmpty()) {
            bounds = dropBoundsInsideSpans(bounds, charSpans);
        }
        if (bounds.isEmpty()) {
            return LegacyTextSplitter.split(text, cfg);
        }

        // 追加文档末尾哨兵以便装箱器冲刷；若偏移 0 不在其中则补一个前导边界。
        bounds.add(new Boundary(totalChars, 0));
        if (bounds.get(0).start() != 0) {
            bounds.add(0, new Boundary(0, 0));
        }

        List<Chunk> out = new ArrayList<>();
        int[] seq = new int[]{0};
        int chunkStart = bounds.get(0).start();
        int curEnd = chunkStart;
        int minChunkSize = cfg.chunkSize() / 4;
        if (minChunkSize < 50) {
            minChunkSize = 50;
        }

        for (int i = 1; i < bounds.size(); i++) {
            int nextEnd = bounds.get(i).start();
            int blockLen = nextEnd - curEnd;

            if (blockLen > cfg.chunkSize()) {
                if (curEnd - chunkStart > 0) {
                    appendChunk(out, text, chunkStart, curEnd, seq);
                    chunkStart = curEnd;
                }
                appendOversizeBlock(out, text, curEnd, nextEnd, cfg, seq);
                curEnd = nextEnd;
                chunkStart = nextEnd;
                continue;
            }

            int accumulated = nextEnd - chunkStart;
            if (accumulated > cfg.chunkSize() && curEnd - chunkStart >= minChunkSize) {
                appendChunk(out, text, chunkStart, curEnd, seq);
                // 将重叠起点吸附到最近的语义边界或换行，避免从行中/词中切开。
                chunkStart = applyOverlapAligned(text, curEnd, cfg.chunkOverlap(), bounds);
            }
            curEnd = nextEnd;
        }

        if (curEnd > chunkStart) {
            appendChunk(out, text, chunkStart, curEnd, seq);
        }
        return out;
    }

    /**
     * 扫描文本，按升序返回候选边界位置。同偏移的较低优先级重复项被丢弃。
     *
     * @param text  源文本
     * @param langs 语言提示
     * @return 边界列表（可能为空）
     */
    static List<Boundary> findHeuristicBoundaries(String text, List<String> langs) {
        List<Boundary> bounds = new ArrayList<>();

        // 分页符 —— 最强的单字符边界。
        for (int idx : allCharIndices(text, "\f")) {
            bounds.add(new Boundary(idx, MarkdownPatterns.PRIO_FORM_FEED));
        }

        String[] lines = text.split("\n", -1);
        List<Pattern> chapterPatterns = MarkdownPatterns.chapterPatternsForLangs(langs);
        int pos = 0;
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence) {
                int start = pos;
                boolean added = false;
                for (Pattern pat : chapterPatterns) {
                    if (MarkdownPatterns.matches(pat, line)) {
                        bounds.add(new Boundary(start, MarkdownPatterns.PRIO_CHAPTER_MARKER));
                        added = true;
                        break;
                    }
                }
                if (!added && MarkdownPatterns.matches(MarkdownPatterns.NUMBERED_SECTION, line)) {
                    bounds.add(new Boundary(start, MarkdownPatterns.PRIO_NUMBERED_HEAD));
                    added = true;
                }
                if (!added && MarkdownPatterns.matches(MarkdownPatterns.ALL_CAPS_HEADING, line)) {
                    bounds.add(new Boundary(start, MarkdownPatterns.PRIO_ALL_CAPS_HEADING));
                    added = true;
                }
                if (!added && MarkdownPatterns.matches(MarkdownPatterns.VISUAL_SEPARATOR, line)) {
                    bounds.add(new Boundary(start, MarkdownPatterns.PRIO_VISUAL_SEP));
                    added = true;
                }
                if (!added && MarkdownPatterns.matches(MarkdownPatterns.PAGE_FOOTER, line)) {
                    bounds.add(new Boundary(start, MarkdownPatterns.PRIO_PAGE_FOOTER));
                }
            }
            pos += line.length();
            if (i < lines.length - 1) {
                pos++;
            }
        }

        // 过度空行块（\n{3,}）。在运行起点匹配，使下一段干净地落入。
        Matcher blankMatcher = MarkdownPatterns.EXCESSIVE_BLANKS.matcher(text);
        while (blankMatcher.find()) {
            int start = blankMatcher.end();
            bounds.add(new Boundary(start, MarkdownPatterns.PRIO_BLANK_BLOCK));
        }

        if (bounds.isEmpty()) {
            return List.of();
        }

        // 按位置排序；同偏移保留最高优先级。
        bounds.sort((a, b) -> {
            if (a.start() != b.start()) {
                return Integer.compare(a.start(), b.start());
            }
            return Integer.compare(b.priority(), a.priority());
        });
        List<Boundary> deduped = new ArrayList<>();
        int prev = -1;
        for (Boundary b : bounds) {
            if (b.start() != prev) {
                deduped.add(b);
                prev = b.start();
            }
        }
        return deduped;
    }

    /**
     * 剔除完全落入任一受保护区间（字符偏移）内的边界。位于区间起点/终点的边界保留。
     * spans 须按起点升序。
     */
    private static List<Boundary> dropBoundsInsideSpans(List<Boundary> bounds, List<Span> spans) {
        if (spans.isEmpty()) {
            return bounds;
        }
        List<Boundary> out = new ArrayList<>();
        boundLoop:
        for (Boundary b : bounds) {
            for (Span s : spans) {
                if (s.start() >= b.start()) {
                    break;
                }
                if (b.start() < s.end()) {
                    continue boundLoop;
                }
            }
            out.add(b);
        }
        return out;
    }

    /**
     * 返回 needle（单字符）在 text 中每个起始位置的字符偏移。
     */
    private static List<Integer> allCharIndices(String text, String needle) {
        List<Integer> out = new ArrayList<>();
        if (text == null || needle.isEmpty()) {
            return out;
        }
        int idx = text.indexOf(needle);
        while (idx >= 0) {
            out.add(idx);
            idx = text.indexOf(needle, idx + needle.length());
        }
        return out;
    }

    /**
     * 将 text[start:end] 切片为 Chunk 并加入 out。纯空白切片被跳过。
     */
    private static void appendChunk(List<Chunk> out, String text, int start, int end, int[] seq) {
        if (end <= start) {
            return;
        }
        String raw = text.substring(start, end);
        if (raw.strip().isEmpty()) {
            return;
        }
        out.add(new Chunk(raw, "", seq[0], start, end));
        seq[0]++;
    }

    /**
     * 递归分块本身大于 cfg.chunkSize 的区域，使用 legacy 分割器以尊重内部长度预算与受保护模式。
     */
    private static void appendOversizeBlock(List<Chunk> out, String text, int start, int end,
            SplitterConfig cfg, int[] seq) {
        if (end <= start) {
            return;
        }
        String subText = text.substring(start, end);
        List<Chunk> subs = LegacyTextSplitter.split(subText, cfg);
        for (Chunk s : subs) {
            out.add(new Chunk(s.content(), "", seq[0], start + s.start(), start + s.end()));
            seq[0]++;
        }
    }

    /**
     * 返回下一分块应起始的字符偏移。目标为 {@code curEnd - overlap}，但吸附到最近的
     * 语义边界（2x overlap 内）或前一个换行，避免从行中/词中开始。
     */
    private static int applyOverlapAligned(String text, int curEnd, int overlap, List<Boundary> bounds) {
        if (overlap <= 0) {
            return curEnd;
        }
        int target = curEnd - overlap;
        if (target < 0) {
            target = 0;
        }
        int windowStart = curEnd - 2 * overlap;
        if (windowStart < 0) {
            windowStart = 0;
        }

        int bestBound = -1;
        for (Boundary b : bounds) {
            if (b.start() >= windowStart && b.start() < curEnd && b.start() > bestBound) {
                bestBound = b.start();
            }
        }
        if (bestBound >= 0) {
            return bestBound;
        }

        // 回退：从 target 往前扫描到最近的换行，但不越过 windowStart。
        for (int i = target; i > windowStart && i < text.length(); i--) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }
        return target;
    }
}
