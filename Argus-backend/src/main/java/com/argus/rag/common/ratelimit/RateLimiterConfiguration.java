package com.argus.rag.common.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 通用限流器配置。
 *
 * <p>注册 {@link RateLimitProperties}，并暴露 {@link RedisSlidingWindowRateLimiter} Bean，
 * 供 embed 渠道等业务模块通过依赖注入复用。</p>
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiterConfiguration {

    @Bean
    public RedisSlidingWindowRateLimiter redisSlidingWindowRateLimiter(
            StringRedisTemplate stringRedisTemplate,
            RateLimitProperties properties
    ) {
        return new RedisSlidingWindowRateLimiter(stringRedisTemplate, properties);
    }
}
