# ADR-0001：Redis 滑动窗口限流器（通用组件，只走 Redis，失败开放）

- **状态**：已接受（Accepted）
- **日期**：2026-09-10
- **背景**：Argus 需要为「Embed 渠道」做限流（参考 WeKnora 的 internal/ratelimit/limiter.go 与 internal/middleware/embed_auth.go）。当前 Argus 既没有 Embed 渠道实体，也没有通用限流器。本决策先把一个**通用、只走 Redis** 的滑动窗口限流器组件落地；Embed 渠道实体与 EmbedAuth 式中间件**另起任务**，本次不涉及。

## 决策

1. **只实现 Redis 方案**：使用 Redis ZSET + Lua 脚本做滑动窗口；**不做本地内存降级**（用户明确要求不考虑降级策略）。
2. **失败开放（fail-open）**：Redis 调用异常时，放行请求并记录 warn 日志，避免限流器自身成为可用性单点。代价是 Redis 故障期间限流保护失效（用户已接受）。
3. **API 形态**：`boolean allow(String key, Duration window, int max)`，window 作为每次调用的参数——一个 Bean 即可服务分钟 / 天两种窗口。`max <= 0` 直接放行（与 WeKnora 一致）。
4. **配置**：通过 `@ConfigurationProperties(prefix = "argus.rag.ratelimit")` 配置，默认键前缀 `embed:ratelimit:`（仿照现有 AuthProperties）。
5. **键的维度命名空间交由调用方**：如 `day:<channel>`、`<channel>:__global`、`<channel>:<ip>`，避免分钟键与天键碰撞，同时保持组件通用。

## 结果

- **优点**：组件通用、单 Bean 支持多窗口；贴合 Argus 现有 StringRedisTemplate 用法；fail-open 保证主功能可用性；无本地降级的复杂度。
- **缺点**：Redis 故障期间限流失效；键命名责任外移，误用可能相互冲突；要验证 Lua 真实语义需真实 Redis（测试用本地 Redis，不可用时跳过）。
- **后续**：Embed 中间件在调用该组件时，需按「渠道 + 维度」构造不冲突的键，并复用键前缀。
