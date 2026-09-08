package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档级结构画像，对应 WeKnora {@code chunker.DocProfile}。
 *
 * <p>一次扫描收集用于分块层选型的指标（标题、编号章节、全大写标题等），
 * 供 {@code AdaptiveStrategySplitter} 在做任何分块决策前使用。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public final class DocProfile {

    private int totalChars;
    private int totalLines;
    private double avgLineLen;
    private double stdLineLen;
    private final Map<Integer, Integer> mdHeadingCounts = new HashMap<>();
    private int mdHeadingTotal;
    private int numberedSectionCount;
    private int allCapsShortLineCount;
    private int blankParagraphBreaks;
    private int formFeedCount;
    private int visualSepCount;
    private int germanChapterCount;
    private int englishChapterCount;
    private int chineseChapterCount;
    private int repeatedFooterCount;
    private boolean hasTables;
    private boolean hasCode;
    private double codeRatio;
    private final List<String> detectedLangs = new ArrayList<>();

    private DocProfile() {
    }

    /**
     * @return 总字符数
     */
    public int totalChars() {
        return totalChars;
    }

    /**
     * @return 总行数
     */
    public int totalLines() {
        return totalLines;
    }

    /**
     * @return 平均行长
     */
    public double avgLineLen() {
        return avgLineLen;
    }

    /**
     * @return 行长标准差
     */
    public double stdLineLen() {
        return stdLineLen;
    }

    /**
     * @return 各层级（1..6）标题数量
     */
    public Map<Integer, Integer> mdHeadingCounts() {
        return new HashMap<>(mdHeadingCounts);
    }

    /**
     * @return 标题总数
     */
    public int mdHeadingTotal() {
        return mdHeadingTotal;
    }

    /**
     * @return 编号章节行数
     */
    public int numberedSectionCount() {
        return numberedSectionCount;
    }

    /**
     * @return 短全大写行数
     */
    public int allCapsShortLineCount() {
        return allCapsShortLineCount;
    }

    /**
     * @return 空段落断点数
     */
    public int blankParagraphBreaks() {
        return blankParagraphBreaks;
    }

    /**
     * @return 换页符数量
     */
    public int formFeedCount() {
        return formFeedCount;
    }

    /**
     * @return 视觉分隔线数量
     */
    public int visualSepCount() {
        return visualSepCount;
    }

    /**
     * @return 德语章节标记行数
     */
    public int germanChapterCount() {
        return germanChapterCount;
    }

    /**
     * @return 英语章节标记行数
     */
    public int englishChapterCount() {
        return englishChapterCount;
    }

    /**
     * @return 中文章节标记行数
     */
    public int chineseChapterCount() {
        return chineseChapterCount;
    }

    /**
     * @return 重复页脚数
     */
    public int repeatedFooterCount() {
        return repeatedFooterCount;
    }

    /**
     * @return 是否含表格
     */
    public boolean hasTables() {
        return hasTables;
    }

    /**
     * @return 是否含代码块
     */
    public boolean hasCode() {
        return hasCode;
    }

    /**
     * @return 代码字符占比
     */
    public double codeRatio() {
        return codeRatio;
    }

    /**
     * @return 检测到的语言提示
     */
    public List<String> detectedLangs() {
        return new ArrayList<>(detectedLangs);
    }

    /**
     * @return 标题行占所有行的比例
     */
    public double headingDensity() {
        if (totalLines == 0) {
            return 0;
        }
        return (double) mdHeadingTotal / totalLines;
    }

    /**
     * 主导标题层级。
     * <p>优先：出现至少 3 次的最高可见层级（真实结构主干）；
     * 否则取最深出现过的层级（为仅 H1 + 若干 H2 的小文档提供更细边界）。</p>
     *
     * @return 主导层级（1..6），无标题返回 0
     */
    public int dominantHeadingLevel() {
        if (mdHeadingTotal == 0) {
            return 0;
        }
        for (int level = 1; level <= 6; level++) {
            if (mdHeadingCounts.getOrDefault(level, 0) >= 3) {
                return level;
            }
        }
        for (int level = 6; level >= 1; level--) {
            if (mdHeadingCounts.getOrDefault(level, 0) > 0) {
                return level;
            }
        }
        return 0;
    }

    /**
     * @return 非 Markdown 结构的标记总数
     */
    public int heuristicMarkerTotal() {
        return numberedSectionCount + germanChapterCount + englishChapterCount + chineseChapterCount
                + allCapsShortLineCount + visualSepCount + formFeedCount;
    }

    /**
     * 对文本执行单次扫描并返回画像。
     *
     * @param text 文档文本
     * @return 文档画像
     */
    public static DocProfile profile(String text) {
        DocProfile p = new DocProfile();
        if (text == null || text.isEmpty()) {
            return p;
        }
        p.totalChars = text.length();
        p.formFeedCount = countOccurrences(text, "\f");

        String[] lines = text.split("\n", -1);
        p.totalLines = lines.length;

        List<Double> lengths = new ArrayList<>();
        boolean inFence = false;
        int codeChars = 0;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                p.hasCode = true;
                continue;
            }
            if (inFence) {
                codeChars += line.length();
                continue;
            }
            int lineLen = line.length();
            lengths.add((double) lineLen);

            MarkdownPatterns.Heading h = MarkdownPatterns.matchHeading(line);
            if (h != null) {
                p.mdHeadingCounts.merge(h.level(), 1, Integer::sum);
                p.mdHeadingTotal++;
                continue;
            }
            if (MarkdownPatterns.matches(MarkdownPatterns.NUMBERED_SECTION, line)) {
                p.numberedSectionCount++;
            }
            if (MarkdownPatterns.matches(MarkdownPatterns.GERMAN_CHAPTER, line)) {
                p.germanChapterCount++;
            }
            if (MarkdownPatterns.matches(MarkdownPatterns.ENGLISH_CHAPTER, line)) {
                p.englishChapterCount++;
            }
            if (MarkdownPatterns.matches(MarkdownPatterns.CHINESE_CHAPTER, line)) {
                p.chineseChapterCount++;
            }
            if (MarkdownPatterns.matches(MarkdownPatterns.ALL_CAPS_HEADING, line)) {
                p.allCapsShortLineCount++;
            }
            if (MarkdownPatterns.matches(MarkdownPatterns.VISUAL_SEPARATOR, line)) {
                p.visualSepCount++;
            }
            if (MarkdownPatterns.matches(MarkdownPatterns.PAGE_FOOTER, line)) {
                p.repeatedFooterCount++;
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                p.hasTables = true;
            }
        }

        if (!lengths.isEmpty()) {
            double sum = 0;
            for (double l : lengths) {
                sum += l;
            }
            p.avgLineLen = sum / lengths.size();
            double variance = 0;
            for (double l : lengths) {
                double d = l - p.avgLineLen;
                variance += d * d;
            }
            variance /= lengths.size();
            p.stdLineLen = Math.sqrt(variance);
        }

        if (p.totalChars > 0) {
            p.codeRatio = (double) codeChars / p.totalChars;
        }
        p.blankParagraphBreaks = countOccurrences(text, "\n\n\n");

        String sample = text;
        if (sample.length() > 4096) {
            sample = sample.substring(0, 4096);
        }
        String lang = TokenEstimator.detectLanguage(sample);
        p.detectedLangs.add(lang);
        if (TokenEstimator.LANG_MIXED.equals(lang)) {
            p.detectedLangs.add(TokenEstimator.LANG_ENGLISH);
            p.detectedLangs.add(TokenEstimator.LANG_GERMAN);
            p.detectedLangs.add(TokenEstimator.LANG_CHINESE);
        }
        return p;
    }

    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
