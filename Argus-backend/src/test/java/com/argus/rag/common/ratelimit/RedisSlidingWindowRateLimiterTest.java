package com.argus.rag.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSlidingWindowRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisSlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setKeyPrefix("embed:ratelimit:");
        limiter = new RedisSlidingWindowRateLimiter(redisTemplate, properties);
    }

    @Test
    void allowSkipsWhenMaxIsNonPositiveAndDoesNotTouchRedis() {
        assertThat(limiter.allow("k", Duration.ofMinutes(1), 0)).isTrue();
        assertThat(limiter.allow("k", Duration.ofMinutes(1), -1)).isTrue();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void allowBuildsScopedKeyFromPrefixAndCallerKey() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class))).thenReturn(1L);

        assertThat(limiter.allow("channel:1.2.3.4", Duration.ofMinutes(1), 30)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                keysCaptor.capture(),
                any(Object[].class));
        assertThat(keysCaptor.getValue()).containsExactly("embed:ratelimit:channel:1.2.3.4");
    }

    @Test
    void allowReturnsFalseWhenScriptDenies() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class))).thenReturn(0L);

        assertThat(limiter.allow("channel", Duration.ofMinutes(1), 3)).isFalse();
    }

    @Test
    void allowFailsOpenWhenRedisErrors() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class))).thenThrow(new RuntimeException("connection refused"));

        assertThat(limiter.allow("channel", Duration.ofMinutes(1), 3)).isTrue();
    }
}
