package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文感知的表头追踪器，对应 WeKnora {@code chunker.header_tracker.go}。
 *
 * <p>当大型 Markdown 表格被拆分为多个分块时，首个分块之后的每个分块都会丢失
 * 表格列名上下文。此追踪器识别表格头，并通知合并逻辑将其前置到后续分块。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
final class HeaderTracker {

    /** Markdown 表格钩子的优先级 */
    private static final int MARKDOWN_TABLE_HOOK_PRIORITY = 15;

    /** 表格头 + 分隔行起始模式 */
    private static final Pattern TABLE_START_PATTERN = Pattern.compile(
            "(?si)^\\s*(?:\\|[^|\\n]*)+[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?[\\r\\n]+$");

    /** 表格头结束模式：空行或不以 | / 空白开头的行 */
    private static final Pattern TABLE_END_PATTERN = Pattern.compile(
            "(?si)^\\s*$|^\\s*[^|\\s].*$");

    /** 单行 Markdown 表格行 */
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\|[^|\\n]*)++\\|\\s*$");

    /** 钩子定义：起始模式、结束模式、优先级 */
    private record HeaderHook(Pattern startPattern, Pattern endPattern, int priority) {
    }

    /** 默认钩子集合（目前仅表格） */
    private static final List<HeaderHook> DEFAULT_HOOKS = List.of(
            new HeaderHook(TABLE_START_PATTERN, TABLE_END_PATTERN, MARKDOWN_TABLE_HOOK_PRIORITY));

    private final List<HeaderHook> hooks;
    /** priority -> header text，活跃表头 */
    private final Map<Integer, String> activeHeaders = new LinkedHashMap<>();
    /** 已结束的表头优先级 */
    private final Map<Integer, Boolean> endedHeaders = new LinkedHashMap<>();
    /** 列名为空的表头，等待首个数据行 */
    private final Map<Integer, Boolean> pendingExtend = new LinkedHashMap<>();
    /** 表头行以段落断点结尾，等待下一单元决定是否开启新表 */
    private boolean pendingTableBreak;
    /** 表示本单元应当先 flush（新表开始或列错配） */
    private boolean headerEndedThisUnit;

    private HeaderTracker() {
        this.hooks = DEFAULT_HOOKS;
    }

    /**
     * @return 新建追踪器
     */
    static HeaderTracker newTracker() {
        return new HeaderTracker();
    }

    /**
     * 检查分块文本中的表头起止标记并更新内部状态。
     *
     * @param split 单元文本
     */
    void update(String split) {
        headerEndedThisUnit = false;

        if (pendingTableBreak) {
            pendingTableBreak = false;
            if (activeHeaders.containsKey(MARKDOWN_TABLE_HOOK_PRIORITY)) {
                if (firstTableRowColumnCount(split) > 0) {
                    clearTableHeader();
                    headerEndedThisUnit = true;
                } else {
                    clearTableHeader();
                }
            }
        }

        // 1. Check for header-end markers among currently active headers.
        for (HeaderHook hook : hooks) {
            if (activeHeaders.containsKey(hook.priority())) {
                if (matches(hook.endPattern(), split)) {
                    endedHeaders.put(hook.priority(), true);
                    activeHeaders.remove(hook.priority());
                    pendingExtend.remove(hook.priority());
                }
            }
        }

        // 1b. Paragraph splits consume the blank line between tables.
        if (activeHeaders.containsKey(MARKDOWN_TABLE_HOOK_PRIORITY)) {
            if (!pendingExtend.containsKey(MARKDOWN_TABLE_HOOK_PRIORITY)) {
                if (splitEndsWithParagraphBreak(split)) {
                    pendingTableBreak = true;
                } else {
                    endTableHeaderOnColumnMismatch(split);
                }
            }
        }

        // 2. Empty column-name rows are replaced by the first data row.
        for (Integer p : new ArrayList<>(pendingExtend.keySet())) {
            if (activeHeaders.containsKey(p) && matches(TABLE_ROW_PATTERN, split)) {
                String separator = extractSeparatorLine(activeHeaders.get(p));
                activeHeaders.put(p, split + separator);
            }
            pendingExtend.remove(p);
        }

        // 3. Check for new header-start markers.
        for (HeaderHook hook : hooks) {
            if (activeHeaders.containsKey(hook.priority())) {
                continue;
            }
            if (endedHeaders.containsKey(hook.priority())) {
                continue;
            }
            Matcher matcher = hook.startPattern().matcher(split);
            if (matcher.find()) {
                String loc = matcher.group();
                activeHeaders.put(hook.priority(), loc);
                if (isEmptyTableHeaderRow(loc)) {
                    pendingExtend.put(hook.priority(), true);
                }
            }
        }

        // 4. If all headers ended, clear the ended set so future tables can be tracked.
        if (activeHeaders.isEmpty()) {
            endedHeaders.clear();
        }
    }

    /**
     * @return 当前所有活跃表头按优先级降序拼接，以换行分隔
     */
    String getHeaders() {
        if (activeHeaders.isEmpty()) {
            return "";
        }
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(activeHeaders.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<Integer, String> e) -> e.getKey()).reversed());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(entries.get(i).getValue());
        }
        return sb.toString();
    }

    /**
     * @return 本单元是否应当先 flush（新表或列错配）
     */
    boolean headerEndedThisUnit() {
        return headerEndedThisUnit;
    }

    private void clearTableHeader() {
        endedHeaders.put(MARKDOWN_TABLE_HOOK_PRIORITY, true);
        activeHeaders.remove(MARKDOWN_TABLE_HOOK_PRIORITY);
        pendingExtend.remove(MARKDOWN_TABLE_HOOK_PRIORITY);
    }

    private void endTableHeaderOnColumnMismatch(String split) {
        String header = activeHeaders.get(MARKDOWN_TABLE_HOOK_PRIORITY);
        if (header == null) {
            return;
        }
        int rowCols = firstTableRowColumnCount(split);
        int headerCols = headerTableColumnCount(header);
        if (rowCols > 0 && headerCols > 0 && rowCols != headerCols) {
            clearTableHeader();
            headerEndedThisUnit = true;
        }
    }

    private static boolean matches(Pattern pattern, String text) {
        return pattern.matcher(text).find();
    }

    private static boolean splitEndsWithParagraphBreak(String split) {
        String trimmed = split.replaceAll("[ \\t\\r]+$", "");
        return trimmed.endsWith("\n\n") || trimmed.endsWith("\r\n\r\n");
    }

    private static boolean isEmptyTableHeaderRow(String header) {
        int idx = header.indexOf('\n');
        if (idx < 0) {
            return false;
        }
        String row = header.substring(0, idx).trim();
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch != '|' && ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return true;
    }

    private static String extractSeparatorLine(String header) {
        for (String line : header.split("\n", -1)) {
            if (line.contains("---")) {
                return line + "\n";
            }
        }
        return "";
    }

    /** 统计单个 Markdown 表格行的列数 */
    static int tableRowColumnCount(String line) {
        line = line.trim();
        if (!line.startsWith("|")) {
            return 0;
        }
        String[] parts = line.split("\\|", -1);
        List<String> list = new ArrayList<>(Arrays.asList(parts));
        if (!list.isEmpty() && list.get(0).trim().isEmpty()) {
            list.remove(0);
        }
        if (!list.isEmpty() && list.get(list.size() - 1).trim().isEmpty()) {
            list.remove(list.size() - 1);
        }
        return list.size();
    }

    /** 从多行文本中取首个表格行的列数 */
    static int firstTableRowColumnCount(String text) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line;
            if (trimmed.isBlank()) {
                continue;
            }
            if (matches(TABLE_ROW_PATTERN, trimmed)) {
                return tableRowColumnCount(trimmed);
            }
        }
        return 0;
    }

    /** 统计表格头的列数（跳过分隔行） */
    static int headerTableColumnCount(String header) {
        for (String line : header.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.contains("---")) {
                continue;
            }
            int n = tableRowColumnCount(trimmed);
            if (n > 0) {
                return n;
            }
        }
        return 0;
    }

    /**
     * 下一单元是否开启了列数与活跃表头不同的新表格。
     *
     * @param headers  活跃表头文本
     * @param nextUnit 下一单元文本
     * @return 是否列错配
     */
    static boolean headerColumnMismatch(String headers, String nextUnit) {
        int headerCols = headerTableColumnCount(headers);
        int rowCols = firstTableRowColumnCount(nextUnit);
        return headerCols > 0 && rowCols > 0 && headerCols != rowCols;
    }
}

