package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 递归文本分割器（Tier 3 / legacy），对应 WeKnora {@code chunker.splitter.go}。
 *
 * <p>核心能力：保护不可分割的原子内容（LaTeX / Markdown / 代码块 / 表格），
 * 按分隔符优先级递归分割，并在合并时维护语义重叠与表格表头上下文。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
final class LegacyTextSplitter {

    /** 受保护单元最大尺寸（字符），超出时强制二次分割 */
    private static final int MAX_PROTECTED_SIZE = 7500;
    /** 合并阶段单分块绝对上限 */
    private static final int ABSOLUTE_MAX_SIZE = 7500;
    /** 语义重叠窗口最多回看的分隔符字符长度（最长分隔符 {@code \r\n\r\n}） */
    private static final int SEMANTIC_OVERLAP_LOOKBEHIND = 4;

    private LegacyTextSplitter() {
    }

    /** 重叠计算返回值：单元集合与字符长度 */
    private record Overlap(List<SplitUnit> units, int length) {
    }

    /** 语义重叠边界：结束偏移 + 是否找到 */
    private record SemanticBoundary(int end, boolean found) {
    }

    /** 候选边界累加器 */
    private static final class Best {
        int start = -1;
        int end = -1;
        int priority = Integer.MAX_VALUE;
        boolean found;
    }

    /**
     * 将文本拆分为分块，尊重受保护模式与重叠。
     *
     * @param text 源文本
     * @param cfg  分块配置
     * @return 分块列表
     */
    public static List<Chunk> split(String text, SplitterConfig cfg) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int chunkSize = cfg.chunkSize() <= 0 ? SplitterConfig.DEFAULT_CHUNK_SIZE : cfg.chunkSize();
        int chunkOverlap = cfg.chunkOverlap() < 0 ? 0 : cfg.chunkOverlap();
        List<String> separators = cfg.separators();

        // Step 1: Find protected spans (UTF-16 char indices).
        List<Span> protectedSpans = ProtectedSpans.find(text);
        // Step 2: Split non-protected regions by separators, keep protected as atomic units.
        List<SplitUnit> units = buildUnitsWithProtection(text, protectedSpans, separators, chunkSize);
        // Step 3: Merge units into chunks with overlap.
        return mergeUnits(units, chunkSize, chunkOverlap);
    }

    /**
     * 按分隔符优先级递归分割文本。{@code chunkSize == 0} 时禁用递归守卫。
     *
     * @param text       待分割文本
     * @param separators 分隔符优先级列表
     * @param chunkSize  目标块大小（字符），0 表示不约束
     * @return 分割片段列表
     */
    static List<String> splitBySeparators(String text, List<String> separators, int chunkSize) {
        if (text == null || text.isEmpty() || separators == null || separators.isEmpty()) {
            return List.of(text == null ? "" : text);
        }
        if (chunkSize > 0 && text.length() <= chunkSize) {
            return List.of(text);
        }

        for (int i = 0; i < separators.size(); i++) {
            String sep = separators.get(i);
            if (sep == null || sep.isEmpty()) {
                continue;
            }
            Pattern regex = Pattern.compile("(" + Pattern.quote(sep) + ")");
            Matcher matcher = regex.matcher(text);
            List<String> matches = new ArrayList<>();
            while (matcher.find()) {
                matches.add(matcher.group());
            }
            if (matches.isEmpty()) {
                continue;
            }

            String[] splits = regex.split(text, -1);
            List<String> pieces = new ArrayList<>();
            for (int j = 0; j < splits.length; j++) {
                if (!splits[j].isEmpty()) {
                    pieces.add(splits[j]);
                }
                if (j < matches.size() && !matches.get(j).isEmpty()) {
                    pieces.add(matches.get(j));
                }
            }
            if (pieces.size() <= 1) {
                continue;
            }

            // Recursively split any piece still too large with remaining (lower-priority) separators.
            List<String> out = new ArrayList<>();
            List<String> remaining = separators.subList(i + 1, separators.size());
            for (String p : pieces) {
                if (chunkSize > 0 && p.length() > chunkSize && !remaining.isEmpty()) {
                    out.addAll(splitBySeparators(p, remaining, chunkSize));
                } else {
                    out.add(p);
                }
            }
            return out;
        }
        return List.of(text);
    }

    /**
     * 将文本拆分为单元，受保护区间保持原子。返回单元的 {@code start}/{@code end}
     * 为 UTF-16 字符偏移。超出 {@link #MAX_PROTECTED_SIZE} 的受保护内容被强制二次分割。
     *
     * @param text           源文本
     * @param protectedSpans 受保护区间（UTF-16 字符索引）
     * @param separators     分隔符列表
     * @param chunkSize      目标块大小
     * @return 单元列表
     */
    static List<SplitUnit> buildUnitsWithProtection(String text, List<Span> protectedSpans,
            List<String> separators, int chunkSize) {
        List<SplitUnit> units = new ArrayList<>();
        int pos = 0; // UTF-16 char index

        for (Span p : protectedSpans) {
            if (p.start() > pos) {
                String pre = text.substring(pos, p.start());
                List<String> parts = splitBySeparators(pre, separators, chunkSize);
                int offset = pos;
                for (String part : parts) {
                    int partLen = part.length();
                    units.add(new SplitUnit(part, offset, offset + partLen));
                    offset += partLen;
                }
                pos = p.start();
            }

            String protText = text.substring(p.start(), p.end());
            int protLen = protText.length();

            if (protLen > MAX_PROTECTED_SIZE) {
                int offset = 0;
                while (offset < protLen) {
                    int chunkEnd = offset + MAX_PROTECTED_SIZE;
                    if (chunkEnd > protLen) {
                        chunkEnd = protLen;
                    } else {
                        for (int i = chunkEnd - 1; i > offset && i > chunkEnd - 200; i--) {
                            if (protText.charAt(i) == '\n' || protText.charAt(i) == ' ') {
                                chunkEnd = i + 1;
                                break;
                            }
                        }
                    }
                    units.add(new SplitUnit(
                            protText.substring(offset, chunkEnd),
                            pos + offset,
                            pos + offset + (chunkEnd - offset)));
                    offset = chunkEnd;
                }
            } else {
                units.add(new SplitUnit(protText, pos, pos + protLen));
            }
            pos = p.end();
        }

        if (pos < text.length()) {
            String remaining = text.substring(pos);
            List<String> parts = splitBySeparators(remaining, separators, chunkSize);
            int offset = pos;
            for (String part : parts) {
                int partLen = part.length();
                units.add(new SplitUnit(part, offset, offset + partLen));
                offset += partLen;
            }
        }
        return units;
    }

    /**
     * 将单元合并为分块，带重叠跟踪与表格表头上下文前置。
     *
     * @param units        单元列表
     * @param chunkSize    目标块大小
     * @param chunkOverlap 重叠窗口
     * @return 分块列表
     */
    static List<Chunk> mergeUnits(List<SplitUnit> units, int chunkSize, int chunkOverlap) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }

        HeaderTracker ht = HeaderTracker.newTracker();
        List<Chunk> chunks = new ArrayList<>();
        List<SplitUnit> current = new ArrayList<>();
        int curLen = 0;

        for (SplitUnit u : units) {
            int uLen = u.len();

            // Oversize single unit: split it further.
            if (uLen > ABSOLUTE_MAX_SIZE) {
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(current, chunks.size()));
                    current = new ArrayList<>();
                    curLen = 0;
                }
                ht.update(u.text());
                String uText = u.text();
                int offset = 0;
                while (offset < uText.length()) {
                    int chunkEnd = offset + ABSOLUTE_MAX_SIZE;
                    if (chunkEnd > uText.length()) {
                        chunkEnd = uText.length();
                    } else {
                        for (int i = chunkEnd - 1; i > offset && i > chunkEnd - 200; i--) {
                            if (uText.charAt(i) == '\n' || uText.charAt(i) == ' ') {
                                chunkEnd = i + 1;
                                break;
                            }
                        }
                    }
                    chunks.add(new Chunk(uText.substring(offset, chunkEnd),
                            chunks.size(), u.start() + offset, u.start() + chunkEnd));
                    offset = chunkEnd;
                }
                continue;
            }

            ht.update(u.text());
            if (ht.headerEndedThisUnit() && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size()));
                current = new ArrayList<>();
                curLen = 0;
            }

            String headers = ht.getHeaders();
            int headersLen = headers.length();
            if (headersLen > chunkSize) {
                headers = "";
                headersLen = 0;
            }

            // If adding this unit (plus reserving header space) exceeds chunk size, flush.
            if (curLen + uLen + headersLen > chunkSize && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size()));

                // Keep overlap from the end of current.
                Overlap ov = computeOverlap(current, chunkOverlap, chunkSize, uLen);
                current = new ArrayList<>(ov.units());
                curLen = ov.length();

                // Shrink overlap further if needed to fit headers + next unit.
                if (!headers.isEmpty() && headersLen + uLen <= chunkSize) {
                    while (!current.isEmpty() && curLen + uLen + headersLen > chunkSize) {
                        curLen -= current.get(0).len();
                        current = new ArrayList<>(current.subList(1, current.size()));
                    }
                    String overlapText = unitsText(current);
                    if (!headerAlreadyPresent(headers, overlapText, u.text())
                            && !HeaderTracker.headerColumnMismatch(headers, u.text())) {
                        int startPos = u.start();
                        if (!current.isEmpty()) {
                            startPos = current.get(0).start();
                        }
                        SplitUnit hUnit = new SplitUnit(headers, startPos, startPos);
                        current.add(0, hUnit);
                        curLen += headersLen;
                    }
                }
            }

            // Absolute overflow guard.
            if (curLen + uLen > ABSOLUTE_MAX_SIZE) {
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(current, chunks.size()));
                    current = new ArrayList<>();
                    curLen = 0;
                }
            }

            current.add(u);
            curLen += uLen;
        }

        if (!current.isEmpty()) {
            chunks.add(buildChunk(current, chunks.size()));
        }
        return chunks;
    }

    /**
     * 拼接所有单元的文本。
     */
    private static String unitsText(List<SplitUnit> units) {
        StringBuilder sb = new StringBuilder();
        for (SplitUnit u : units) {
            sb.append(u.text());
        }
        return sb.toString();
    }

    /**
     * 从单元列表构建分块：内容为拼接文本，位置为整体跨度。
     */
    private static Chunk buildChunk(List<SplitUnit> units, int seq) {
        StringBuilder sb = new StringBuilder();
        for (SplitUnit u : units) {
            sb.append(u.text());
        }
        return new Chunk(sb.toString(), seq, units.get(0).start(), units.get(units.size() - 1).end());
    }

    /**
     * 计算语义重叠后缀（字符长度与单元集合）。
     *
     * <p>配置的重叠是硬上限而非原始字符切片。边界检测可观察窗口前最多
     * {@link #SEMANTIC_OVERLAP_LOOKBEHIND} 个额外字符，以保证被窗口截断的分隔符
     * 仍然可见。优先级：段落断点 &gt; 换行 &gt; 句子结束。</p>
     */
    private static Overlap computeOverlap(List<SplitUnit> current, int chunkOverlap, int chunkSize, int nextLen) {
        if (chunkOverlap <= 0) {
            return new Overlap(List.of(), 0);
        }
        int maxOverlap = chunkOverlap;
        int remaining = chunkSize - nextLen;
        if (remaining < maxOverlap) {
            maxOverlap = remaining;
        }
        if (maxOverlap <= 0) {
            return new Overlap(List.of(), 0);
        }

        List<SplitUnit> window = semanticOverlapWindow(current, maxOverlap + SEMANTIC_OVERLAP_LOOKBEHIND);
        if (window.isEmpty()) {
            return new Overlap(List.of(), 0);
        }

        String windowText = unitsText(window);
        int originalWindowStart = windowText.length() - maxOverlap;
        if (originalWindowStart < 0) {
            originalWindowStart = 0;
        }
        SemanticBoundary boundary = findSemanticOverlapBoundaryEndingAtOrAfter(windowText, originalWindowStart);
        if (!boundary.found()) {
            return new Overlap(List.of(), 0);
        }

        List<SplitUnit> overlap = trimUnitsPrefix(window, boundary.end());
        int overlapLen = 0;
        for (SplitUnit u : overlap) {
            overlapLen += u.len();
        }
        if (overlapLen <= 0 || overlapLen > maxOverlap || unitsText(overlap).strip().isEmpty()) {
            return new Overlap(List.of(), 0);
        }
        return new Overlap(overlap, overlapLen);
    }

    /**
     * 返回 current 末尾最多 {@code maxLen} 个源文本字符的窗口。可在首个保留单元内部切开，
     * 以便发现大段中的语义边界。合成零宽单元（重复表头）构成硬屏障。
     */
    private static List<SplitUnit> semanticOverlapWindow(List<SplitUnit> current, int maxLen) {
        if (maxLen <= 0 || current.isEmpty()) {
            return List.of();
        }
        int remaining = maxLen;
        List<SplitUnit> reversed = new ArrayList<>();
        for (int i = current.size() - 1; i >= 0 && remaining > 0; i--) {
            SplitUnit u = current.get(i);
            int uLen = u.len();
            if (uLen == 0) {
                continue;
            }
            // Header markers contain generated text but occupy no source range.
            if (u.start() == u.end() || u.end() - u.start() != uLen) {
                break;
            }
            if (uLen <= remaining) {
                reversed.add(u);
                remaining -= uLen;
                continue;
            }
            int start = uLen - remaining;
            reversed.add(new SplitUnit(u.text().substring(start, uLen), u.start() + start, u.end()));
            remaining = 0;
        }

        if (reversed.isEmpty()) {
            return List.of();
        }
        List<SplitUnit> window = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            window.add(reversed.get(i));
        }
        return window;
    }

    /**
     * 在窗口文本中寻找早于或等于 {@code minEnd} 的语义重叠边界结束偏移。
     */
    private static SemanticBoundary findSemanticOverlapBoundaryEndingAtOrAfter(String text, int minEnd) {
        int len = text.length();
        if (len == 0) {
            return new SemanticBoundary(0, false);
        }
        if (minEnd < 0) {
            minEnd = 0;
        }
        if (minEnd > len) {
            return new SemanticBoundary(0, false);
        }

        List<Span> protectedSpans = ProtectedSpans.find(text);
        Best best = new Best();

        // Mark paragraph-break chars so their component newlines are not also
        // emitted as lower-priority line-break candidates.
        boolean[] paragraphChar = new boolean[len];
        for (int i = 0; i < len; i++) {
            if (i + 3 < len && text.charAt(i) == '\r' && text.charAt(i + 1) == '\n'
                    && text.charAt(i + 2) == '\r' && text.charAt(i + 3) == '\n') {
                considerBoundary(i, i + 4, 1, minEnd, len, text, protectedSpans, best);
                for (int j = i; j < i + 4; j++) {
                    paragraphChar[j] = true;
                }
                i += 3;
            } else if (i + 1 < len && text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
                considerBoundary(i, i + 2, 1, minEnd, len, text, protectedSpans, best);
                paragraphChar[i] = true;
                paragraphChar[i + 1] = true;
                i++;
            }
        }

        for (int i = 0; i < len; i++) {
            if (paragraphChar[i]) {
                continue;
            }
            if (text.charAt(i) == '\r' && i + 1 < len && text.charAt(i + 1) == '\n' && !paragraphChar[i + 1]) {
                considerBoundary(i, i + 2, 2, minEnd, len, text, protectedSpans, best);
                i++;
                continue;
            }
            if (text.charAt(i) == '\n') {
                considerBoundary(i, i + 1, 2, minEnd, len, text, protectedSpans, best);
            }
        }

        for (int i = 0; i < len; i++) {
            switch (text.charAt(i)) {
                case '。', '？', '！' -> considerBoundary(i, i + 1, 3, minEnd, len, text, protectedSpans, best);
                case '.', '?', '!' -> {
                    if (i + 1 < len && text.charAt(i + 1) == ' ') {
                        considerBoundary(i, i + 2, 3, minEnd, len, text, protectedSpans, best);
                    }
                }
                default -> {
                }
            }
        }

        if (!best.found) {
            return new SemanticBoundary(0, false);
        }
        return new SemanticBoundary(best.end, true);
    }

    private static void considerBoundary(int start, int end, int priority, int minEnd, int len,
            String text, List<Span> protectedSpans, Best best) {
        if (start < 0 || end <= start || end < minEnd || end > len) {
            return;
        }
        if (insideProtected(protectedSpans, start)) {
            return;
        }
        if (!hasMeaningfulTail(text, end)) {
            return;
        }
        if (!best.found || priority < best.priority
                || (priority == best.priority && start < best.start)) {
            best.start = start;
            best.end = end;
            best.priority = priority;
            best.found = true;
        }
    }

    private static boolean insideProtected(List<Span> spans, int pos) {
        for (Span p : spans) {
            if (pos < p.start()) {
                return false;
            }
            if (pos >= p.start() && pos < p.end()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMeaningfulTail(String text, int end) {
        return end >= 0 && end < text.length()
                && !text.substring(end).strip().isEmpty();
    }

    /**
     * 移除前缀 {@code prefixLen} 个源字符，同时保留剩余单元的原始位置。
     *
     * @param units     单元列表
     * @param prefixLen 相对单元文本的前缀长度（字符）
     * @return 裁剪后的单元列表
     */
    private static List<SplitUnit> trimUnitsPrefix(List<SplitUnit> units, int prefixLen) {
        if (prefixLen <= 0) {
            return new ArrayList<>(units);
        }
        int remaining = prefixLen;
        List<SplitUnit> out = new ArrayList<>();
        for (SplitUnit u : units) {
            int uLen = u.len();
            if (remaining >= uLen) {
                remaining -= uLen;
                continue;
            }
            if (remaining > 0) {
                out.add(new SplitUnit(
                        u.text().substring(remaining),
                        u.start() + remaining,
                        u.end()));
                remaining = 0;
            } else {
                out.add(u);
            }
        }
        return out;
    }

    /**
     * 表头是否已存在于重叠或下一单元中，防止重复。
     */
    private static boolean headerAlreadyPresent(String headers, String overlapText, String unitText) {
        if (overlapText.contains(headers) || unitText.contains(headers)) {
            return true;
        }
        String colRow = headerColumnRow(headers);
        if (colRow.isEmpty()) {
            return false;
        }
        return overlapText.contains(colRow) || unitText.contains(colRow);
    }

    /**
     * 从表头字符串中提取列名行（首个有意义的非分隔行）。
     */
    private static String headerColumnRow(String header) {
        for (String line : header.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.contains("---")) {
                continue;
            }
            boolean onlyPipes = true;
            for (int i = 0; i < trimmed.length(); i++) {
                char ch = trimmed.charAt(i);
                if (ch != '|' && ch != ' ' && ch != '\t') {
                    onlyPipes = false;
                    break;
                }
            }
            if (!onlyPipes) {
                return trimmed;
            }
        }
        return "";
    }
}
