package com.argus.rag.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级限流注解，配合 {@link RateLimitAspect} 生效。
 *
 * <p>标注在 Spring Bean（通常是 Controller）的方法上，AOP 切面会在目标方法执行
 * 前调用 {@link RedisSlidingWindowRateLimiter} 做滑动窗口限流；超限时抛出
 * {@code RateLimitException}，由全局异常处理器映射为 HTTP 429。</p>
 *
 * <p><b>注意事项</b>：</p>
 * <ul>
 *   <li>仅对经由 Spring 代理的外部调用生效（同类内部 {@code this.method()} 自调用不触发）。</li>
 *   <li>{@link #key()} 作为业务前缀叠加在全局键前缀之后；为空时退化为 {@code 类名#方法名}。</li>
 *   <li>限流失败采用 fail-open：Redis 不可用时放行（由 {@link RedisSlidingWindowRateLimiter} 保证）。</li>
 *   <li>{@link #rate()} 与 {@link #rateInterval()} 语义为「每 {@code rateInterval} 秒允许 {@code rate} 次请求」。</li>
 * </ul>
 */
@Documented
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流 key 前缀（业务维度标识），叠加在全局键前缀之后。
     * 为空时退化为 {@code 类名#方法名}，保证同一维度内键唯一。
     */
    String key() default "";

    /** 每个时间窗口允许的请求数，{@code <= 0} 表示不限流。 */
    int rate() default 10;

    /** 时间窗口（秒）。 */
    int rateInterval() default 1;

    /** 限流类型（维度）。 */
    RateLimitType limitType() default RateLimitType.USER;

    /** 超限提示信息。 */
    String message() default "请求过于频繁，请稍后再试";
}
