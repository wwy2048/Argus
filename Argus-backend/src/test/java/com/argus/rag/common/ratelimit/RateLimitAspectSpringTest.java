package com.argus.rag.common.ratelimit;

import com.argus.rag.common.exception.RateLimitException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用真实 Spring AOP 代理验证 {@code @RateLimit} 注解能被切面拦截：
 * 第一次放行，第二次（脚本返回 0）抛出限流异常。
 */
class RateLimitAspectSpringTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void annotationIsWovenThroughSpringAop() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L)
                .thenReturn(0L);

        context = new AnnotationConfigApplicationContext();
        context.registerBean(StringRedisTemplate.class, () -> template);
        context.register(TestConfig.class);
        context.refresh();

        RateLimitedTarget target = context.getBean(RateLimitedTarget.class);

        assertThat(target.hello()).isEqualTo("hello");
        assertThatThrownBy(() -> target.hello())
                .isInstanceOf(RateLimitException.class)
                .hasMessage("limited");
    }

    @Configuration
    @EnableAspectJAutoProxy
    @ComponentScan("com.argus.rag.common.ratelimit")
    static class TestConfig {

        @Bean
        RateLimitedTarget rateLimitedTarget() {
            return new RateLimitedTarget();
        }
    }

    static class RateLimitedTarget {

        @RateLimit(key = "ctx", rate = 5, rateInterval = 60, limitType = RateLimitType.API, message = "limited")
        public String hello() {
            return "hello";
        }
    }
}
