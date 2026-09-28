# Context

本文件定义本仓库（Argus）的领域词汇与关键背景，供工程技能在探索代码库时使用。
这是**初始草稿**，由 `/domain-modeling` 技能在解析真实项目术语与决策时持续完善。

## 领域词汇表（Glossary）

| 术语 | 含义 |
| --- | --- |
| 用户 (User) | 系统中的一个账户实体，对应 `users` 表，含状态（`UserStatus`）。 |
| 角色 (Role) | 用户在系统中承担的权限角色，如 `SystemRole`、`GroupRole`。 |
| 认证 (Auth) | 登录 / 注册 / 刷新令牌 / 登出流程，基于 JWT 访问令牌与刷新令牌。 |
| 访问令牌 (Access Token) | 短期有效的 JWT，随请求以 Bearer 头携带。 |
| 刷新令牌 (Refresh Token) | 用于换取新访问令牌的长期凭证，以 httpOnly cookie 存储于 `user_refresh_tokens` 表。 |
| RAG | 检索增强生成，本项目的核心领域。 |
| 限流 (Rate Limiting) | 对某个维度（如 IP、渠道、用户）在指定时间窗内允许的请求数量上限。 |
| 滑动窗口限流器 (Sliding-Window Rate Limiter) | 在时间窗口内按请求时间戳计数、越界即拒绝的限流器；窗口随时间滑动，不按固定周期重置。 |
| 失败开放 (Fail-Open) | 限流后端（如 Redis）不可用时放行请求，避免限流器自身成为单点故障拖垮主功能；代价是故障期间限流保护失效。 |
| 限流键 (Rate-Limit Key) | 标识一条限流计数记录的键，由「键前缀 + 调用方维度键」组成。 |
| Embed 渠道 (Embed Channel) | 目标概念：供外部网页嵌入的问答 / 助手渠道，将绑定到知识库群组与助手；本次仅为其铺设通用限流器，渠道实体与中间件另起任务。 |
| 限流注解 (`@RateLimit`) | 方法级注解，声明某接口的限流维度、时间窗口与预算；由 AOP 切面在方法执行前调用滑动窗口限流器判定是否放行。 |
| 限流维度类型 (`RateLimitType`) | 枚举：`API`（全局限流）、`USER`（按登录用户）、`IP`（按客户端 IP）；决定限流键的维度后缀（`:user:<id>` / `:ip:<ip>` / 无后缀）。 |
| 限流异常 (`RateLimitException`) | 限流超限时抛出的运行时异常，message 取自 `@RateLimit.message()`，由全局异常处理器映射为 HTTP 429。 |
| 429 (Too Many Requests) | 限流超限的 HTTP 状态码；Argus 全局异常处理器新增该映射以返回 `ApiResponse`。 |

## 相关设计决策（ADRs）

ADR 位于 `docs/adr/`。当你的输出与现有 ADR 冲突时，应显式指出，而非静默覆盖。


