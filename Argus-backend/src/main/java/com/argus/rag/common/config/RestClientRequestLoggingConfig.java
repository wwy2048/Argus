package com.argus.rag.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/**
 * 调试辅助：把 RestClient（Spring AI 的 OpenAI 兼容调用）实际发出的 HTTP 请求打印到日志。
 * <p>
 * Spring 6 的 RestClient 不像 RestTemplate 那样内建 DEBUG 请求日志，走的是 Micrometer
 * Observation，因此 {@code logging.level.org.springframework.web.client.RestClient: DEBUG}
 * 不会打印出请求 URL / body。本类通过 ClientHttpRequestInterceptor 手动打印，便于排查
 * 下游 404 / 模型名不存在等问题。
 * <p>
 * 注意：headers 会打印出 Authorization 明文，仅用于本地调试，验证完请删除本类或降级。
 */
@Configuration
public class RestClientRequestLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientRequestLoggingConfig.class);

    private static final int MAX_BODY_CHARS = 1200;

    @Bean
    RestClientCustomizer loggingRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            log.info(">>> HTTP {} {}", request.getMethod(), request.getURI());
            request.getHeaders().forEach((name, values) -> log.info(">>>   {}: {}", name, values));

            String bodyStr = body == null ? "<empty>" : new String(body, StandardCharsets.UTF_8);
            if (bodyStr.length() > MAX_BODY_CHARS) {
                bodyStr = bodyStr.substring(0, MAX_BODY_CHARS) + " ...(截断, 共 " + body.length + " 字节)";
            }
            log.info(">>>   Body: {}", bodyStr);

            var response = execution.execute(request, body);
            log.info("<<< Response status: {}", response.getStatusCode());
            return response;
        });
    }
}
