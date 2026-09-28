package com.argus.rag.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通用限流器配置。
 *
 * <p>键形如 {@code {keyPrefix}{调用方键}}。调用方通过键带入维度命名空间，
 * 例如 {@code day:<channel>}、{@code <channel>:__global}、{@code <channel>:<ip>}，
 * 以避免不同窗口（分钟 / 天）的键相互碰撞。</p>
 */
@ConfigurationProperties(prefix = "argus.rag.ratelimit")
public class RateLimitProperties {

    /** Redis 限流键前缀，默认指向 embed 渠道场景。 */
    private String keyPrefix = "embed:ratelimit:";

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
