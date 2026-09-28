package com.argus.rag.common.ratelimit;

/**
 * 限流维度类型，决定 {@link RateLimit} 生成键时附加的维度标识。
 *
 * <p>最终 Redis 键由三部分拼接：{@link RateLimitProperties#getKeyPrefix()} 全局前缀
 * + {@link RateLimit#key()} 业务前缀 + 维度后缀。</p>
 *
 * <ul>
 *   <li>{@link #API}：全局限流，维度后缀为空（键仅由前缀拼成）。</li>
 *   <li>{@link #USER}：按当前登录用户限流，维度后缀为 {@code :user:<userId>}；
 *       未登录时退化为 {@code :user:anon}。</li>
 *   <li>{@link #IP}：按客户端 IP 限流，维度后缀为 {@code :ip:<ip>}。</li>
 * </ul>
 */
public enum RateLimitType {

    /** 接口级别限流 */
    API,

    /** 用户级别限流 */
    USER,

    /** IP 级别限流 */
    IP
}
