package com.argus.rag.common.ratelimit;

import com.argus.rag.common.exception.RateLimitException;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * {@link RateLimit} 注解的 AOP 切面。
 *
 * <p>拦截带 {@code @RateLimit} 的方法：按 {@link RateLimitType} 构造维度键，调用
 * {@link RedisSlidingWindowRateLimiter#allow(String, Duration, int)} 判定是否放行；
 * 超限时抛出 {@link RateLimitException}（HTTP 429）。</p>
 *
 * <p>键结构：{@code {全局前缀}{业务前缀(或 类名#方法名)}{维度后缀}}。</p>
 *
 * <p>限流失败采用 fail-open（Redis 异常放行），该语义由 {@link RedisSlidingWindowRateLimiter} 保证，
 * 切面不重复实现降级。</p>
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final String UNKNOWN = "unknown";
    private static final String ANON = "anon";

    private final RedisSlidingWindowRateLimiter limiter;

    public RateLimitAspect(RedisSlidingWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = resolveKey(joinPoint, rateLimit);
        Duration window = Duration.ofSeconds(rateLimit.rateInterval());
        boolean allowed = limiter.allow(key, window, rateLimit.rate());
        if (!allowed) {
            throw new RateLimitException(rateLimit.message());
        }
        return joinPoint.proceed();
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        String base = StringUtils.hasText(rateLimit.key()) ? rateLimit.key() : defaultApiKey(joinPoint);
        return switch (rateLimit.limitType()) {
            case API -> base;
            case USER -> base + ":user:" + currentUserId();
            case IP -> base + ":ip:" + resolveClientIp();
        };
    }

    private String defaultApiKey(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "#" + signature.getName();
    }

    private String currentUserId() {
        AuthenticatedUser user = UserContext.get();
        if (user == null || user.userId() == null) {
            return ANON;
        }
        return String.valueOf(user.userId());
    }

    private String resolveClientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return UNKNOWN;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddr) ? remoteAddr : UNKNOWN;
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }
}
