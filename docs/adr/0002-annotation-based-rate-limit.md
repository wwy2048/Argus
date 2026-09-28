# ADR-0002：注解式限流（@RateLimit + AOP 切面）

- **状态**：已接受（Accepted）
- **日期**：2026-09-10
- **背景**：ADR-0001 落地了通用 Redis 滑动窗口限流器组件（`RedisSlidingWindowRateLimiter`）。为了让业务方（尤其是 Controller）以声明式方式复用该组件，需求进一步要求：把限流做成一个**方法级注解**，参考常见 `@RateLimit` 形态，支持 API / 用户 / IP 三种维度。

## 决策

1. **新增 `RateLimitType` 枚举**：`API`（全局限流）、`USER`（按登录用户）、`IP`（按客户端 IP）。
2. **新增 `@RateLimit` 注解**（`@Target(METHOD)`、`@Retention(RUNTIME)`）：
   - `key()`：业务前缀；为空时退化为 `类名#方法名`，保证同一维度内键唯一。
   - `rate()`：每个时间窗口允许的请求数，`<= 0` 表示不限流（沿用到 `RedisSlidingWindowRateLimiter` 既有语义）。
   - `rateInterval()`：时间窗口（秒）。
   - `limitType()`：限流维度，默认 `USER`。
   - `message()`：超限提示信息。
3. **新增 `RateLimitAspect`**（`@Aspect @Component`）：拦截带 `@RateLimit` 的方法。
   - 键结构：`{全局键前缀}{注解 key 或 类名#方法名}{维度后缀}`，其中维度后缀：`API`→空、`USER`→`:user:<userId>`（未登录为 `:user:anon`）、`IP`→`:ip:<ip>`。
   - 客户端 IP 依次取 `X-Forwarded-For` 首段、`X-Real-IP`、`getRemoteAddr()`，均缺失为 `unknown`。
   - 调用 `RedisSlidingWindowRateLimiter.allow(key, Duration.ofSeconds(rateInterval), rate)`；返回 `false` 时抛出 `RateLimitException`。
   - **复用 fail-open**：Redis 异常放行由 limiter 保证，切面不重复实现降级。
4. **新增 `RateLimitException`**（`common.exception`）：继承 `RuntimeException`，由全局异常处理器映射为 HTTP 429。
5. **扩展 `GlobalExceptionHandler`**：新增 `RateLimitException` → `@ResponseStatus(TOO_MANY_REQUESTS)`，返回统一 `ApiResponse`。

## 结果

- **优点**：声明式、低侵入；复用既有通用限流器；维度键自动构造；429 语义与 `ApiResponse` 保持一致。
- **缺点 / 注意**：仅对经 Spring 代理的外部调用生效（同类内 `this.method()` 自调用不触发）；接口级 `key()` 为空时默认键含类名与方法名，跨类需避免前缀碰撞；`rateInterval` 为秒，依赖调用方语义。
- **后续**：Embed 渠道中间件仍可直接注入 `RedisSlidingWindowRateLimiter` 走三层限流；注解用于 Controller 等 HTTP 入口的 API / 用户 / IP 限流。
