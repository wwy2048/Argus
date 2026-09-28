package com.argus.rag.common.ratelimit;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.RateLimitException;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private RedisSlidingWindowRateLimiter limiter;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private HttpServletRequest request;

    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RateLimitAspect(limiter);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserContext.clear();
    }

    @Test
    void userLimitAppendsUserIdAndCallsLimiter() throws Throwable {
        RateLimit annotation = annotationOf(RateLimitAspectTest.class, "userLimit");
        UserContext.set(new AuthenticatedUser(42L, "u1", "name", SystemRole.USER, false));
        when(limiter.allow(anyString(), any(Duration.class), anyInt())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, annotation);

        assertThat(result).isEqualTo("ok");
        verify(limiter).allow(eq("chat:user:42"), eq(Duration.ofSeconds(60)), eq(5));
    }

    @Test
    void userLimitWithNoLoginFallsBackToAnon() throws Throwable {
        RateLimit annotation = annotationOf(RateLimitAspectTest.class, "userLimit");
        when(limiter.allow(anyString(), any(Duration.class), anyInt())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        verify(limiter).allow(eq("chat:user:anon"), any(Duration.class), anyInt());
    }

    @Test
    void ipLimitUsesForwardedForHeader() throws Throwable {
        RateLimit annotation = annotationOf(RateLimitAspectTest.class, "ipLimit");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.allow(anyString(), any(Duration.class), anyInt())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        verify(limiter).allow(eq("chat:ip:1.2.3.4"), eq(Duration.ofSeconds(1)), eq(10));
    }

    @Test
    void ipLimitFallsBackToRemoteAddrWhenNoForwardedHeader() throws Throwable {
        RateLimit annotation = annotationOf(RateLimitAspectTest.class, "ipLimit");
        when(request.getRemoteAddr()).thenReturn("9.9.9.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(limiter.allow(anyString(), any(Duration.class), anyInt())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        verify(limiter).allow(eq("chat:ip:9.9.9.9"), any(Duration.class), anyInt());
    }

    @Test
    void apiLimitWithEmptyKeyUsesClassAndMethod() throws Throwable {
        RateLimit annotation = annotationOf(RateLimitAspectTest.class, "apiLimit");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) RateLimitAspectTest.class);
        when(signature.getName()).thenReturn("apiLimit");
        when(limiter.allow(anyString(), any(Duration.class), anyInt())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint, annotation);

        verify(limiter).allow(eq("RateLimitAspectTest#apiLimit"), eq(Duration.ofSeconds(1)), eq(10));
    }

    @Test
    void deniedThrowsRateLimitExceptionWithMessage() {
        RateLimit annotation = annotationOf(RateLimitAspectTest.class, "userLimit");
        UserContext.set(new AuthenticatedUser(1L, "u", "name", SystemRole.USER, false));
        when(limiter.allow(anyString(), any(Duration.class), anyInt())).thenReturn(false);

        assertThatThrownBy(() -> aspect.around(joinPoint, annotation))
                .isInstanceOf(RateLimitException.class)
                .hasMessage("too many");
    }

    private RateLimit annotationOf(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName).getAnnotation(RateLimit.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @RateLimit(key = "chat", rate = 5, rateInterval = 60, limitType = RateLimitType.USER, message = "too many")
    public void userLimit() {
    }

    @RateLimit(key = "chat", limitType = RateLimitType.IP)
    public void ipLimit() {
    }

    @RateLimit(limitType = RateLimitType.API)
    public void apiLimit() {
    }
}
