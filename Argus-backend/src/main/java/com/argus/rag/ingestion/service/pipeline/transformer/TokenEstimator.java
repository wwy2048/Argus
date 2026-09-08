package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.Map;

/**
 * 语言感知的 token 计数近似，对应 WeKnora {@code chunker.tokens.go}。
 *
 * <p>无 tokenizer 依赖，使用按语言的 chars/token 比率（偏保守）估算，
 * 使分块安全地处于嵌入模型上限之下。长度与偏移均按 Java UTF-16 字符数计。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 语言标识 */
    public static final String LANG_ENGLISH = "en";
    public static final String LANG_GERMAN = "de";
    public static final String LANG_CHINESE = "zh";
    public static final String LANG_MIXED = "mixed";

    /** 每个 token 对应的近似字符数（保守取值） */
    private static final Map<String, Double> CHARS_PER_TOKEN = Map.of(
            LANG_ENGLISH, 4.0,
            LANG_GERMAN, 4.5,
            LANG_CHINESE, 1.7,
            LANG_MIXED, 3.0);

    /**
     * @param textLen 字符数（UTF-16 code unit 长度）
     * @param lang    语言标识
     * @return 保守的 token 估计
     */
    public static int approxTokenCountFromLen(int textLen, String lang) {
        if (textLen <= 0) {
            return 0;
        }
        double ratio = CHARS_PER_TOKEN.getOrDefault(lang, CHARS_PER_TOKEN.get(LANG_MIXED));
        double approx = textLen / ratio;
        if (approx < 1) {
            return 1;
        }
        return (int) Math.round(approx);
    }

    /**
     * @param s    文本
     * @param lang 语言标识
     * @return 保守 token 估计
     */
    public static int approxTokenCount(String s, String lang) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return approxTokenCountFromLen(s.length(), lang);
    }

    /**
     * 粗略语言检测：统计 CJK 与 Latin 码点，区分 zh / de / en / mixed。
     *
     * @param s 文本
     * @return 语言标识
     */
    public static String detectLanguage(String s) {
        if (s == null || s.isEmpty()) {
            return LANG_MIXED;
        }
        int cjk = 0;
        int latin = 0;
        int umlaut = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                cjk++;
            } else if (isGermanUmlaut(cp)) {
                umlaut++;
                latin++;
            } else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
                latin++;
            }
        }
        int total = cjk + latin;
        if (total == 0) {
            return LANG_MIXED;
        }
        double cjkRatio = (double) cjk / total;
        double latinRatio = (double) latin / total;
        if (cjkRatio >= 0.15 && latinRatio >= 0.15) {
            return LANG_MIXED;
        }
        if (cjkRatio > 0.3) {
            return LANG_CHINESE;
        }
        if (umlaut > 0 || hasGermanWords(s)) {
            return LANG_GERMAN;
        }
        return LANG_ENGLISH;
    }

    /**
     * @param cp 码点
     * @return 是否为 CJK（汉字/谚文/假名）
     */
    private static boolean isCjk(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL
                || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA;
    }

    /**
     * @param cp 码点
     * @return 是否德文变音字符
     */
    static boolean isGermanUmlaut(int cp) {
        return cp == 'ä' || cp == 'ö' || cp == 'ü' || cp == 'Ä' || cp == 'Ö' || cp == 'Ü' || cp == 'ß';
    }

    /**
     * 轻量德文停用词检查，偏向 "de"。
     *
     * @param s 文本
     * @return 是否含有常见德文虚词
     */
    private static boolean hasGermanWords(String s) {
        int sample = Math.min(512, s.length());
        String sampleText = s.substring(0, sample);
        for (String w : new String[]{" der ", " die ", " das ", " und ", " ist ", " nicht ", " mit ", " auf "}) {
            if (containsLower(sampleText, w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 不区分大小写地判断 {@code needle} 是否出现在 {@code haystack} 中。
     *
     * @param haystack 源串
     * @param needle   目标串
     * @return 是否包含
     */
    private static boolean containsLower(String haystack, String needle) {
        if (haystack.length() < needle.length()) {
            return false;
        }
        for (int i = 0; i + needle.length() <= haystack.length(); i++) {
            boolean match = true;
            for (int j = 0; j < needle.length(); j++) {
                char h = haystack.charAt(i + j);
                if (h >= 'A' && h <= 'Z') {
                    h += 'a' - 'A';
                }
                if (h != needle.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 token 上限转换为对应语言的近似字符预算。
     *
     * @param tokens token 上限
     * @param lang   语言标识
     * @return 字符预算（0 表示无效）
     */
    public static int charsForTokenLimit(int tokens, String lang) {
        if (tokens <= 0) {
            return 0;
        }
        double ratio = CHARS_PER_TOKEN.getOrDefault(lang, CHARS_PER_TOKEN.get(LANG_MIXED));
        // 0.9 安全系数，使结果偏向低估模型上限。
        return (int) (tokens * ratio * 0.9);
    }
}
