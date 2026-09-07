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

## 相关设计决策（ADRs）

ADR 位于 `docs/adr/`。当你的输出与现有 ADR 冲突时，应显式指出，而非静默覆盖。
