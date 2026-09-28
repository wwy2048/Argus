package com.argus.rag.common.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 连接本地真实 Redis（localhost:6379）验证 Lua 滑动窗口语义。
 * <p>未运行 Redis 时自动跳过，不影响无 Redis 环境的 CI。</p>
 */
class RedisSlidingWindowRateLimiterIT {

    private static final String HOST = "localhost";
    private static final int PORT = 6379;
    private static final String PREFIX = "it:ratelimit:";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisSlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        assumeTrue(isReachable(HOST, PORT), "本地 Redis 未运行，跳过集成测试");

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
        config.setDatabase(0);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        RateLimitProperties properties = new RateLimitProperties();
        properties.setKeyPrefix(PREFIX);
        limiter = new RedisSlidingWindowRateLimiter(redisTemplate, properties);
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            Set<String> keys = redisTemplate.keys(PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void allowsUpToBudgetThenDeniesWithinWindow() {
        assertThat(limiter.allow("ch:ip", Duration.ofMinutes(1), 3)).isTrue();
        assertThat(limiter.allow("ch:ip", Duration.ofMinutes(1), 3)).isTrue();
        assertThat(limiter.allow("ch:ip", Duration.ofMinutes(1), 3)).isTrue();
        assertThat(limiter.allow("ch:ip", Duration.ofMinutes(1), 3)).isFalse();
    }

    @Test
    void keysAreIndependent() {
        assertThat(limiter.allow("a", Duration.ofMinutes(1), 1)).isTrue();
        assertThat(limiter.allow("a", Duration.ofMinutes(1), 1)).isFalse();
        assertThat(limiter.allow("b", Duration.ofMinutes(1), 1)).isTrue();
    }

    @Test
    void allowsAgainAfterWindowSlides() throws InterruptedException {
        Duration window = Duration.ofMillis(200);
        assertThat(limiter.allow("ch:global", window, 2)).isTrue();
        assertThat(limiter.allow("ch:global", window, 2)).isTrue();
        assertThat(limiter.allow("ch:global", window, 2)).isFalse();

        // 窗口完全滑动后，旧成员被剔除，重新放行。
        Thread.sleep(350);
        assertThat(limiter.allow("ch:global", window, 2)).isTrue();
    }

    @Test
    void nonPositiveMaxAlwaysAllows() {
        assertThat(limiter.allow("ch", Duration.ofMinutes(1), 0)).isTrue();
    }

    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
