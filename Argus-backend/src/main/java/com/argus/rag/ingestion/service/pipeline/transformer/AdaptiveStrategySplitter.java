package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.ArrayList;
import java.util.List;

/**
 * 自适应三层策略链分块入口，对应 WeKnora {@code chunker.strategy.go}。
 *
 * <p>调用方通过 {@link #split} 而非 legacy {@code SplitText} 提交文本；策略解析器根据
 * 文档画像与 {@code SplitterConfig.strategy} 提示挑选层级。返回结果永不为空：
 * 层级失败时链条回退到 legacy 分割器（原始 Tier-3 实现）。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public final class AdaptiveStrategySplitter {

    private AdaptiveStrategySplitter() {
    }

    /** 策略链条 + 驱动选型的文档画像（auto 策略时非 null） */
    private record StrategyChain(List<StrategyTier> tiers, DocProfile profile) {
    }

    /**
     * 使用 cfg 中配置的策略切分文本。当 cfg.strategy 为空或 "auto" 时由文档画像挑选层级。
     *
     * @param text 源文本
     * @param cfg  分块配置
     * @return 分块列表（永不为空）
     */
    public static List<Chunk> split(String text, SplitterConfig cfg) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        SplitterConfig normalized = ensureDefaults(cfg);
        StrategyChain chain = resolveChainWithProfile(text, normalized);
        List<StrategyTier> tiers = chain.tiers();
        DocProfile profile = chain.profile();
        int totalChars = text.length();

        List<Chunk> lastOut = null;
        for (int i = 0; i < tiers.size(); i++) {
            StrategyTier tier = tiers.get(i);
            List<Chunk> out = runTier(tier, text, normalized, profile);
            ChunkValidator.ValidationResult v = ChunkValidator.validate(out, totalChars, normalized.chunkSize());
            if (v.ok()) {
                return out;
            }
            if (tier == StrategyTier.LEGACY && i == tiers.size() - 1) {
                lastOut = out;
            }
        }
        if (lastOut != null) {
            return lastOut;
        }
        // 防御性兜底：始终确保至少一块。
        return LegacyTextSplitter.split(text, normalized);
    }

    /**
     * 返回待尝试的策略链条；当链条由图像（auto 策略）选择时同时返回驱动选型的 DocProfile。
     */
    private static StrategyChain resolveChainWithProfile(String text, SplitterConfig cfg) {
        String strategy = cfg.strategy();
        switch (strategy) {
            case SplitterConfig.STRATEGY_HEADING:
                return new StrategyChain(List.of(StrategyTier.HEADING, StrategyTier.LEGACY), null);
            case SplitterConfig.STRATEGY_HEURISTIC:
                return new StrategyChain(List.of(StrategyTier.HEURISTIC, StrategyTier.LEGACY), null);
            case SplitterConfig.STRATEGY_RECURSIVE:
                // "recursive" 是 "legacy" 的公开 API 别名：两者都调用 SplitText。
                return new StrategyChain(List.of(StrategyTier.LEGACY), null);
            case SplitterConfig.STRATEGY_LEGACY:
            case "":
                // 空 == legacy 保持向后兼容，兼容早于 Strategy 字段的存储配置。
                return new StrategyChain(List.of(StrategyTier.LEGACY), null);
            case SplitterConfig.STRATEGY_AUTO:
            default:
                DocProfile profile = DocProfile.profile(text);
                return new StrategyChain(selectStrategy(profile), profile);
        }
    }

    /**
     * 依序尝试的层级链。首层为最优选择；后续层为校验拒绝时的回退。legacy 始终作为终极安全网。
     */
    static List<StrategyTier> selectStrategy(DocProfile profile) {
        if (profile == null) {
            return List.of(StrategyTier.LEGACY);
        }
        List<StrategyTier> chain = new ArrayList<>();

        // Tier-1 候选：Markdown 标题感知。
        if (profile.mdHeadingTotal() >= 3 && profile.headingDensity() > 0.005
                && profile.dominantHeadingLevel() > 0) {
            chain.add(StrategyTier.HEADING);
        }

        // Tier-2 候选：启发式边界检测。
        if (profile.heuristicMarkerTotal() >= 5 || profile.formFeedCount() > 0
                || profile.germanChapterCount() + profile.englishChapterCount()
                + profile.chineseChapterCount() > 0) {
            chain.add(StrategyTier.HEURISTIC);
        }

        // Legacy 作为终极回退：即使校验失败也始终返回分块。
        chain.add(StrategyTier.LEGACY);
        return chain;
    }

    /**
     * 分发到对应层级实现。profile 可为 null（显式非 auto 策略跳过文档画像）；
     * 需要画像的层级按需计算。
     */
    static List<Chunk> runTier(StrategyTier tier, String text, SplitterConfig cfg, DocProfile profile) {
        switch (tier) {
            case HEADING:
                return HeadingSplitter.splitByHeadings(text, cfg, profile);
            case HEURISTIC:
                return HeuristicSplitter.splitByHeuristics(text, cfg, profile);
            case LEGACY:
                return LegacyTextSplitter.split(text, cfg);
            default:
                return LegacyTextSplitter.split(text, cfg);
        }
    }

    /**
     * 填充零值配置字段为稳健默认值。当 cfg.tokenLimit 被设置时，将 ChunkSize 钳制到该
     * token 上限以内的字符预算（含 10% 安全系数），使分块对硬 token 上限的嵌入 API 安全。
     */
    private static SplitterConfig ensureDefaults(SplitterConfig cfg) {
        int chunkSize = cfg.chunkSize();
        int chunkOverlap = cfg.chunkOverlap();
        List<String> separators = cfg.separators();
        if (chunkSize <= 0) {
            chunkSize = SplitterConfig.DEFAULT_CHUNK_SIZE;
        }
        if (chunkOverlap <= 0) {
            chunkOverlap = SplitterConfig.DEFAULT_CHUNK_OVERLAP;
        }
        if (separators == null || separators.isEmpty()) {
            separators = SplitterConfig.DEFAULT_SEPARATORS;
        }
        if (cfg.tokenLimit() > 0) {
            String lang = TokenEstimator.LANG_MIXED;
            if (!cfg.languages().isEmpty()) {
                lang = cfg.languages().get(0);
            }
            int charBudget = TokenEstimator.charsForTokenLimit(cfg.tokenLimit(), lang);
            if (charBudget > 0 && (chunkSize == 0 || charBudget < chunkSize)) {
                chunkSize = charBudget;
            }
        }
        // 防止病态重叠配置：Overlap 超过 ChunkSize/2 时，几乎每个分块都是重复内容。
        if (chunkOverlap > chunkSize / 2 && chunkSize > 0) {
            chunkOverlap = chunkSize / 2;
        }
        return new SplitterConfig(chunkSize, chunkOverlap, separators, cfg.strategy(), cfg.tokenLimit(),
                cfg.languages());
    }

    /**
     * 合并父级与子级标题面包屑为单个 ContextHeader。当子级在父级内容上重跑标题检测时，
     * 其首行面包屑通常与父级末行重复 —— 丢弃该重复，避免嵌入上下文冗余。
     *
     * @param parent 父级面包屑
     * @param child  子级面包屑
     * @return 合并后的面包屑
     */
    static String mergeBreadcrumbs(String parent, String child) {
        if (parent == null || parent.isEmpty()) {
            return child == null ? "" : child;
        }
        if (child == null || child.isEmpty()) {
            return parent;
        }
        String[] parentLines = parent.split("\n", -1);
        String[] childLines = child.split("\n", -1);
        if (parentLines.length > 0 && childLines.length > 0
                && parentLines[parentLines.length - 1].strip().equals(childLines[0].strip())) {
            // 去掉子级首行重复。
            String[] rest = new String[childLines.length - 1];
            System.arraycopy(childLines, 1, rest, 0, rest.length);
            childLines = rest;
        }
        if (childLines.length == 0) {
            return parent;
        }
        return parent + "\n" + String.join("\n", childLines);
    }
}
