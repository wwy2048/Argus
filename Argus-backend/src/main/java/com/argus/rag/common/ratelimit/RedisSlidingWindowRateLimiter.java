package com.argus.rag.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis ZSET + Lua 脚本的滑动窗口限流器。
 *
 * <p>参考 WeKnora {@code internal/ratelimit/limiter.go}：每个请求以时间戳为 score 写入 ZSET，
 * 脚本先 {@code ZREMRANGEBYSCORE} 剔除窗口外的旧成员，再 {@code ZCARD} 计数，未超限则
 * {@code ZADD} 记录命中并 {@code PEXPIRE} 设置过期。窗口随调用移动，不按固定周期重置。</p>
 *
 * <p>仅实现 Redis 方案，<b>不做本地内存降级</b>。调用方通过 {@code key} 携带维度命名空间，
 * 避免不同窗口的键相互冲突。</p>
 *
 * <p><b>失败开放（fail-open）</b>：Redis 调用异常时记录告警并放行，
 * 避免限流器自身成为可用性单点。</p>
 */
public class RedisSlidingWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    /**
     * 原子地清理过期成员、统计计数、并在未超限时记录一次命中。
     * 返回 1 表示放行，0 表示拒绝。
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local key     = KEYS[1]
            local now     = tonumber(ARGV[1])
            local window  = tonumber(ARGV[2])
            local maxReq  = tonumber(ARGV[3])
            local member  = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count < maxReq then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window + 1000)
                return 1
            end
            return 0
            """, Long.class);

    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 判断 {@code key} 在当前窗口内是否仍处于预算内。
     *
     * @param key    调用方维度键（Redis 键前缀由配置提供）
     * @param window 时间窗口；{@code null}、零或负值回退为 1 分钟
     * @param max    窗口内允许的最大请求数；{@code <= 0} 表示不限流
     * @return {@code true} 放行；{@code false} 拒绝（调用方应返回 429/拒绝）
     */
    public boolean allow(String key, Duration window, int max) {
        if (max <= 0) {
            return true;
        }
        if (window == null || window.isZero() || window.isNegative()) {
            window = DEFAULT_WINDOW;
        }

        String redisKey = properties.getKeyPrefix() + key;
        long nowMs = System.currentTimeMillis();
        long windowMs = window.toMillis();
        // member 唯一，避免同一毫秒内的多个请求在 ZSET 中因 member 相同而被覆盖导致少计数。
        String member = UUID.randomUUID() + ":" + nowMs;

        try {
            Long result = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(redisKey),
                    String.valueOf(nowMs),
                    String.valueOf(windowMs),
                    String.valueOf(max),
                    member
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            // 失败开放：Redis 不可用时放行并告警，避免限流器拖垮主功能。
            log.warn("限流器 Redis 调用失败，fail-open 放行。key={}, max={}, error={}",
                    redisKey, max, e.getMessage(), e);
            return true;
        }
    }
}
