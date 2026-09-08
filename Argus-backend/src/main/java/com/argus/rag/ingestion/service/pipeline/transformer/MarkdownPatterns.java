package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多语言正则模式与优先级常量，对应 WeKnora {@code chunker.patterns.go}。
 *
 * <p>作为标题感知与启发式分割器共用的模式事实来源。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public final class MarkdownPatterns {

    private MarkdownPatterns() {
    }

    /** 启发式边界的优先级，值越大越强 */
    public static final int PRIO_FORM_FEED = 100;
    public static final int PRIO_NUMBERED_HEAD = 90;
    public static final int PRIO_CHAPTER_MARKER = 85;
    public static final int PRIO_ALL_CAPS_HEADING = 70;
    public static final int PRIO_VISUAL_SEP = 60;
    public static final int PRIO_PAGE_FOOTER = 50;
    public static final int PRIO_BLANK_BLOCK = 40;

    /** ATX 风格 Markdown 标题：捕获组 1 = hashes，2 = 标题文本 */
    public static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** 数字/罗马数字编号章节 */
    public static final Pattern NUMBERED_SECTION = Pattern.compile(
            "(?m)^[ \\t]*(?:\\d+(?:\\.\\d+){1,3}\\.?|(?:\\d+|[IVX]{1,5})\\.)[ \\t]+\\S.{0,200}$");

    /** 短全大写行（无 Markdown 标题的章节标题） */
    public static final Pattern ALL_CAPS_HEADING = Pattern.compile(
            "(?m)^[ \\t]*([A-ZÄÖÜ][A-ZÄÖÜ \\-]{3,80}):?\\s*$");

    /** 水平分隔线 */
    public static final Pattern VISUAL_SEPARATOR = Pattern.compile(
            "(?m)^[ \\t]*(?:-{3,}|={3,}|\\*{3,}|_{3,})[ \\t]*$");

    /** 三个及以上连续换行 */
    public static final Pattern EXCESSIVE_BLANKS = Pattern.compile("\\n{3,}");

    /** 页脚，如 "Seite X von Y" / "Page X of Y" */
    public static final Pattern PAGE_FOOTER = Pattern.compile(
            "(?mi)^[ \\t]*(?:Seite|Page|页码?)\\s+\\d+(?:\\s*(?:von|of|/)\\s*\\d+)?[ \\t]*$");

    /** 德语章节标记 */
    public static final Pattern GERMAN_CHAPTER = Pattern.compile(
            "(?m)^[ \\t]*(?:Kapitel|Abschnitt|Teil)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$");

    /** 英语章节标记 */
    public static final Pattern ENGLISH_CHAPTER = Pattern.compile(
            "(?m)^[ \\t]*(?:Chapter|Section|Part)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$");

    /** 中文章节标记（如 第一章 / 第 1 章） */
    public static final Pattern CHINESE_CHAPTER = Pattern.compile(
            "(?m)^[ \\t]*第[ \\t]*[一二三四五六七八九十百千零〇0-9]+[ \\t]*(?:章|节|節|部分|篇)[ \\t]?.{0,200}$");

    private static final List<Pattern> CHAPTER_PATTERNS = List.of(
            GERMAN_CHAPTER, ENGLISH_CHAPTER, CHINESE_CHAPTER);

    /**
     * @param lang 语言标识
     * @return 该语言的句子级分隔符
     */
    public static List<String> sentenceSeparators(String lang) {
        return switch (lang) {
            case TokenEstimator.LANG_CHINESE -> List.of("。", "！", "？", "；", "\n");
            case TokenEstimator.LANG_GERMAN, TokenEstimator.LANG_ENGLISH ->
                    List.of(". ", "! ", "? ", "; ", "\n");
            default -> List.of("。", "！", "？", "；", ". ", "! ", "? ", "; ", "\n");
        };
    }

    /**
     * @param langs 语言提示；为空/未知时返回全部章节模式
     * @return 适用的章节标记正则列表
     */
    public static List<Pattern> chapterPatternsForLangs(List<String> langs) {
        if (langs == null || langs.isEmpty()) {
            return CHAPTER_PATTERNS;
        }
        List<Pattern> out = new ArrayList<>();
        for (String lang : langs) {
            switch (lang) {
                case TokenEstimator.LANG_GERMAN -> out.add(GERMAN_CHAPTER);
                case TokenEstimator.LANG_ENGLISH -> out.add(ENGLISH_CHAPTER);
                case TokenEstimator.LANG_CHINESE -> out.add(CHINESE_CHAPTER);
                default -> {
                }
            }
        }
        return out.isEmpty() ? CHAPTER_PATTERNS : out;
    }

    /** 标题匹配结果 */
    public record Heading(int level, String text) {
    }

    /**
     * 检测单行是否为 ATX 标题。行不应包含换行符。
     *
     * @param line 单行文本
     * @return 命中则返回层级与标题文本，否则 {@code null}
     */
    public static Heading matchHeading(String line) {
        Matcher m = MARKDOWN_HEADING.matcher(line);
        if (!m.find()) {
            return null;
        }
        int level = m.group(1).length();
        if (level < 1 || level > 6) {
            return null;
        }
        return new Heading(level, m.group(2).strip());
    }

    /**
     * 判断单行是否匹配多行锚定的正则（等价于 Go 的 {@code Pattern.MatchString}）。
     *
     * @param pattern 正则
     * @param line    单行文本
     * @return 是否命中
     */
    public static boolean matches(Pattern pattern, String line) {
        return pattern.matcher(line).find();
    }
}
