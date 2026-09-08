# 自适应三层策略链 MD 分块 — Java 实现说明

> 本文档面向开发/维护人员，解释 `com.argus.rag.ingestion.service.pipeline.transformer` 包下新增的
> **自适应三层策略链（Adaptive Strategy Chain）Markdown 分块**相关的 Java 类。
>
> 逻辑迁移自 WeKnora 的 `internal/infrastructure/chunker`（`strategy.go` / `heading_splitter.go` /
> `heuristic_splitter.go` / `splitter.go` / `validator.go` / `patterns.go` / `tokens.go`），
> 按 Spring AI `DocumentTransformer` 的接口形态接入 Argus。

---

## 1. 目标与背景

长文档（尤其是 Markdown / 结构化文本）直接喂给嵌入模型会因为 token 上限、语义割裂而退化。
因此需要**自适应分块**：先做文档画像，再根据文档结构从三层策略中选一层最合适的，逐层尝试、
校验，失败则降级。这套策略在 WeKnora 中已被生产验证，这里将其移植为 Java。

**核心思想：**

- 一次扫描得到 `DocProfile`（标题数、编号章节数、章节标记、页脚、代码占比等）。
- 按 `strategy` 提示（`auto`/`heading`/`heuristic`/`recursive`/`legacy`）确定策略链。
- 依序执行链上各层，用 `ChunkValidator` 校验输出；不合格则回退到下一层。
- **Legacy（递归）永远是最终安全网**，保证任何输入都返回非空分块。

```text
输入文本
   │
   ▼
DocProfile 文档画像（auto 策略时）
   │
   ▼
策略链解析 resolveChainWithProfile
   │
   ├─ Tier1  HeadingSplitter    （Markdown 标题感知）
   ├─ Tier2  HeuristicSplitter   （启发式边界）
   └─ Tier3  LegacyTextSplitter  （递归分割，兜底）
   │
   ▼
ChunkValidator 校验 → 不通过则降级
   │
   ▼
AdaptiveStrategyChunkTransformer 组装 Spring AI Document
```

---

## 2. 新增类总览

| 类 | 角色 | 对应 Go 源 |
| --- | --- | --- |
| `AdaptiveStrategyChunkTransformer` | `DocumentTransformer` 实现，入口 | (Spring AI 适配层) |
| `AdaptiveStrategySplitter` | 策略链解析 / 选型 / 分发 | `strategy.go` |
| `SplitterConfig` | 分块配置（字符级） | `SplitterConfig` |
| `StrategyTier` | 策略层级枚举 | `StrategyTier` |
| `DocProfile` | 文档结构画像 | `ProfileDocument` / `DocProfile` |
| `ChunkValidator` | 分块结果校验 | `ValidateChunks` |
| `MarkdownPatterns` | 多语言正则模式 & 优先级 | `patterns.go` |
| `TokenEstimator` | 语言感知 token 估算 | `tokens.go` |
| `HeadingHierarchy` | 标题层级栈（面包屑） | `HeadingHierarchy` |
| `HeadingSplitter` | Tier 1 标题感知分块 | `heading_splitter.go` |
| `HeuristicSplitter` | Tier 2 启发式边界分块 | `heuristic_splitter.go` |
| `LegacyTextSplitter` | Tier 3 递归分块 | `splitter.go` |
| `SplitUnit` | 分块过程中的最小文本单元 | `splitUnit` |
| `Span` | 文本区间（UTF-16 字符） | `span` |
| `ProtectedSpans` | 检测不可分割的原子内容 | `protectedSpans` |
| `HeaderTracker` | 表格表头上下文跟踪 | `HeaderTracker` |

> 既有类（未改动）：`StructureAwareChunkTransformer`、`TextCleanupTransformer`、`ChunkingProperties`。

---

## 3. 入口：`AdaptiveStrategyChunkTransformer`

实现 `org.springframework.ai.document.DocumentTransformer`，被 Spring 扫描为 `@Component` bean。

```java
@Component
public class AdaptiveStrategyChunkTransformer implements DocumentTransformer {
    @Override
    public List<Document> apply(List<Document> documents) { ... }
}
```

**处理流程：**

1. 对每个输入 `Document`，取 `document.getText()`。
2. 调用 `AdaptiveStrategySplitter.split(text, cfg)` 得到 `List<Chunk>`。
3. 为每个 `Chunk` 构建一个 Spring AI `Document`，写入元数据。

**每个分块输出的元数据：**

| 键 | 值 | 说明 |
| --- | --- | --- |
| `sectionPath` | `"# 第一章 > ## 1.2"` | 将 `contextHeader` 的换行替换为 `" > "`，作为章节路径 |
| `charStart` | UTF-16 索引 | 分块在原文中的起始位置，可直接用于 `text.substring(charStart, charEnd)` |
| `charEnd` | UTF-16 索引 | 分块在原文中的结束位置（不含） |
| `chunkStrategy` | `"adaptive-strategy-token-v1"` | 分块策略标识 |
| 源元数据 | 复制 | 同时保留源 `Document` 自带元数据 |

**ID 规则：** `sourceId + ":" + chunkIndex`（如 `doc-1:0`）。

**配置映射：** `config()` 方法把 token 级配置（`targetTokens`/`maxTokens`/`overlapTokens`）换算成字符级
`SplitterConfig`：

```java
int chunkSize    = TokenEstimator.charsForTokenLimit(targetTokens, LANG_MIXED); // 目标 token → 字符
int chunkOverlap = TokenEstimator.charsForTokenLimit(overlapTokens, LANG_MIXED);
return new SplitterConfig(chunkSize, chunkOverlap, DEFAULT_SEPARATORS, STRATEGY_AUTO, maxTokens, List.of());
```

> 这里固定用 `LANG_MIXED`（每 token ≈ 3 字符，保守）。也可用 `TokenEstimator.detectLanguage` 按语言更精确。

---

## 4. 策略入口与分发：`AdaptiveStrategySplitter`

静态工具类，是策略链的“大脑”。

### `split(String text, SplitterConfig cfg)`

```java
public static List<Chunk> split(String text, SplitterConfig cfg) {
    if (text.isEmpty()) return List.of();
    cfg = ensureDefaults(cfg);
    StrategyChain chain = resolveChainWithProfile(text, cfg);
    ...
    for (tier : chain) {
        out = runTier(tier, text, cfg, profile);
        if (validate(out).ok()) return out;
    }
    return LegacyTextSplitter.split(text, cfg); // 兜底
}
```

- 结果**永不为空**：任何策略失败都会回退到 legacy。
- `totalChars` 通过 `text.length()` 计算（UTF-16 字符数）。

### `resolveChainWithProfile`：确定策略链

| `cfg.strategy()` | 链 |
| --- | --- |
| `heading` | `[HEADING, LEGACY]` |
| `heuristic` | `[HEURISTIC, LEGACY]` |
| `recursive` (legacy 别名) | `[LEGACY]` |
| `legacy` / `""` | `[LEGACY]` |
| `auto`（默认） | 由 `DocProfile` 生成，见下 |

### `selectStrategy(DocProfile)`：auto 时的选型

```java
if (mdHeadingTotal >= 3 && headingDensity > 0.005 && dominantHeadingLevel > 0) chain.add(HEADING);
if (heuristicMarkerTotal >= 5 || formFeedCount > 0
        || german+english+chinese chapter counts > 0) chain.add(HEURISTIC);
chain.add(LEGACY); // 兜底
```

- **Tier 1 候选**：标题总数 ≥ 3、标题密度 > 0.5%、存在主导层级。
- **Tier 2 候选**：编号章节/章节标记/全大写标题/分隔线/分页符等启发式标记足够多。
- **LEGACY 永远加入**，保证最终有输出。

### `runTier`：分发

```java
switch (tier) {
    case HEADING   -> HeadingSplitter.splitByHeadings(text, cfg, profile);
    case HEURISTIC -> HeuristicSplitter.splitByHeuristics(text, cfg, profile);
    case LEGACY    -> LegacyTextSplitter.split(text, cfg);
}
```

### `ensureDefaults`：补默认值

- `chunkSize`/`chunkOverlap`/`separators` 为 0/空时填默认。
- 若有 `tokenLimit`，用 `charsForTokenLimit` 把块大小钳制到 token 预算以内（含 10% 安全系数）。
- 防止病态重叠：`overlap > chunkSize/2` 时削到 `chunkSize/2`。

### `mergeBreadcrumbs`

父块与子块面包屑拼接时，若子块首行与父块末行重复（子块重跑标题检测所致），去掉重复行。

---

## 5. 核心数据结构

### `SplitterConfig`（record）

```java
public record SplitterConfig(
    int chunkSize, int chunkOverlap, List<String> separators,
    String strategy, int tokenLimit, List<String> languages) { ... }
```

- 常量：`STRATEGY_AUTO`、`DEFAULT_CHUNK_SIZE=512`、`DEFAULT_CHUNK_OVERLAP=80`、默认分隔符 `["\n\n","\n","。"]`。
- 紧凑构造器把空 `strategy` 归一为 `LEGACY`（与 Go “空 == legacy” 语义一致）。

### `Chunk`（record）

```java
public record Chunk(String content, String contextHeader, int seq, int start, int end) {}
```

- `content`：`[start, end)` 原文切片。
- `contextHeader`：独立的面包屑（标题上下文），不占 content 字符预算，保证 `end - start == content` 字符长度不变式（供文档重建依赖）。
- `embeddingContent()`：返回 `contextHeader + "\n\n" + content.strip()`，即真正交给嵌入模型的文本。

### `StrategyTier`（enum）

`HEADING` / `HEURISTIC` / `LEGACY`，带 `code()` 用于元数据标签。

---

## 6. 文档画像：`DocProfile`

`DocProfile.profile(text)` 单次扫描收集选型指标：

- 总字符、总行数、平均/标准差行长
- 各层级标题数量（`mdHeadingCounts`）、标题总数、主导层级 `dominantHeadingLevel`
- 编号章节、全大写短行、空段落断点、换页符、视觉分隔线
- 德/英/中章节标记数、重复页脚数
- 是否含表格/代码、代码占比
- 检测到的语言

关键方法：

```java
double headingDensity()      // 标题总数 / 总行数
int dominantHeadingLevel()   // 出现>=3次的最高层级，否则最深层级
int heuristicMarkerTotal()   // 编号+章节+全大写+分隔线+分页符+换页符 之和
```

---

## 7. Tier 1：`HeadingSplitter`（标题感知）

适用：有清晰 Markdown 标题结构的文档。

**算法：**

1. 取 `dominantHeadingLevel`（主导层级，如 `##` 的 level=2）。
2. `findHeadingBoundaries` 找出标题边界：偏移 0 恒为首边界；每个层级 ≤ 主导层级的标题成为边界（跳过代码围栏内）。
3. 遍历边界，维护 `HeadingHierarchy` 栈，得到当前面包屑 `BreadcrumbWithHashes()`。
4. 每个区间：
   - 若 `面包屑长度 + 2 + 区间长度 <= chunkSize` → 整块输出（面包屑放 `contextHeader`）。
   - 否则交给 `LegacyTextSplitter` 做内部分割，每个子块用 `sectionBreadcrumbs` 计算其起始处最深标题的面包屑。
5. `coalesceTinyChunks`：合并相邻过小分块（需 `cur.end == next.start` 且共享标题前缀），避免触发校验器“too many tiny chunks”。

**边界用例：** 无标题结构或只产生单个区间时，直接回退 legacy。

---

## 8. Tier 2：`HeuristicSplitter`（启发式边界）

适用：缺少标题但有结构线索（分页符、编号章节、章节标记、视觉分隔线、全大写标题、页脚）的文档。

**算法：**

1. `findHeuristicBoundaries` 收集候选边界（带优先级：分页符 100 > 编号 90 > 章节 85 > 全大写 70 > 分隔线 60 > 页脚 50 > 空行块 40）。
2. 用 `ProtectedSpans` 剔除落在受保护区间（表格/代码块/LaTeX 块）内部的边界。
3. 追加文档末尾哨兵，保证偏移 0 有边界。
4. **贪心装箱**：累积边界间的块，直到加入下一块会超过 `chunkSize`；
   - 若某块本身超过 `chunkSize`，先冲刷当前累积，再把该大块递归交给 legacy。
   - 超过预算时，用 `applyOverlapAligned` 把下一块起点吸附到最近的语义边界或换行，避免从行中/词中切开。
5. 输出时跳过纯空白分块。

> **注意**：`applyOverlapAligned` 的回退逻辑是向“前”扫描最近的换行（Go 用 `i--`），实现中已修正为递减循环，避免从行中截断。

---

## 9. Tier 3：`LegacyTextSplitter`（递归，兜底）

最通用的一层，承载核心递归分割逻辑。

1. 用 `ProtectedSpans.find` 找出不可分割的原子内容（LaTeX 块、图片、链接、表格、代码块、内联代码）。
2. `buildUnitsWithProtection`：受保护区间保持原子；非受保护区按分隔符优先级递归切分。
3. `mergeUnits`：把最小单元合并成块，处理：
   - 语义重叠（`computeOverlap`，在段落断点/换行/句子边界处对齐，而非硬切字符）；
   - 表格表头上下文（`HeaderTracker`，跨块保留表头，`HeaderTracker.headerColumnMismatch` 判断列冲突）；
   - 超过 `ABSOLUTE_MAX_SIZE` 的单元强制二次分割。

---

## 10. 工具类

### 偏移单位：统一使用 Java `char`（UTF-16）

本实现**不使用** rune(码点) 换算，所有偏移（`Chunk.start`/`end`、`SplitUnit.start`/`end`、
`Span.start`/`end`）统一采用 Java `String` 的原生 UTF-16 code unit 索引，
与 `text.substring(start, end)` 直接对应，因此也不需要 `RuneUtil` 这类换算工具。

- 内部算法与对外输出的 `charStart`/`charEnd` 元数据使用同一套字符索引，无二次换算。
- `end - start == content.length()` 位置不变式在字符级别成立，可直接用 `substring` 还原内容。
- 绝大多数文档（中文/BMP 字符）下，一个码点恰为一个 `char`，行为与 Go 的 rune 语义一致；
  对增补平面字符（emoji/生僻汉字等），按 UTF-16 计的长度可能比码点略大，
  使分块预算**略微保守**，但不会出现偏移错位或非法切片。

### `ProtectedSpans` / `Span`

检测**不可分割的原子内容区间**，返回按起点排序、去重叠的区间，供 legacy / heuristic 使用，
避免把表格、代码块、公式从中间切碎。

### `MarkdownPatterns`

多语言正则模式的事实来源，含优先级常量与章节模式（德/英/中）。统一用 `matcher.find()` 模拟 Go `MatchString`。

### `TokenEstimator`

无 tokenizer 依赖，按语言 chars/token 比率估算：
`en≈4`、`de≈4.5`、`zh≈1.7`、`mixed≈3`。`charsForTokenLimit(tokens, lang)` 用于把 token 预算换算成字符预算（含 0.9 安全系数）。

---

## 11. 校验器：`ChunkValidator`

有意宽松，只拒绝“明显损坏”的输出，避免在层级间来回振荡：

| 拒绝条件 | 原因 |
| --- | --- |
| 无分块 | `no chunks produced` |
| 单块且文本远大于 `2*chunkSize` | 策略没真正切分，降级 |
| 非末块 < 50 字符且数量超过 1/4（且 >2） | `too many tiny chunks` |
| 最大块 < `chunkSize/4` 且文长 > `chunkSize` | `all chunks far below target size` |
| 最大块 > `2*chunkSize` | `chunk exceeds 2x target size` |

---

## 12. 与既有代码 / Spring 的衔接

- 所有新类都位于 `com.argus.rag.ingestion.service.pipeline.transformer` 包。
- `AdaptiveStrategyChunkTransformer` 是 `@Component`，构造器注入 `ChunkingProperties`（由
  `DocumentIngestionConfiguration` 的 `@EnableConfigurationProperties` 提供）。
- `DocumentIngestionConfiguration` 目前按具体类型注入 `StructureAwareChunkTransformer`，
  **不会**因新增 `AdaptiveStrategyChunkTransformer` 产生 Bean 歧义。
- 若想启用新策略，可在 ingestion 装配中改用 `AdaptiveStrategyChunkTransformer`，或把它加入管线。

---

## 13. 一行数据流示例

```text
Document(text="# 概述\n…正文…")
   → AdaptiveStrategyChunkTransformer.apply
   → AdaptiveStrategySplitter.split(text, SplitterConfig(auto, chunkSize≈1350, overlap≈216))
   → DocProfile.profile(text)        // 检测到标题结构
   → resolveChainWithProfile → [HEADING, LEGACY]
   → HeadingSplitter.splitByHeadings → 多个 Chunk(内容, 面包屑, start, end)
   → ChunkValidator.validate → ok
   → Document(id="src:0", text=embeddingContent(), metadata={sectionPath, charStart, charEnd, chunkStrategy})
```

---

## 14. 后续可扩展

- **未移植**：`SplitParentChild`（父块→子块重组）、`SplitWithDiagnostics`（调试/预览端点返回选型诊断）。
  如你需要，可在 `AdaptiveStrategySplitter` 上扩充。
- **语言精确化**：`config()` 目前固定 `LANG_MIXED`，可改为调用 `TokenEstimator.detectLanguage(text)` 按文档语言换算 token → 字符。
- **测试**：建议补一个针对中文/混合 Markdown 的边界单测，覆盖标题嵌套、表格、代码块等场景。
