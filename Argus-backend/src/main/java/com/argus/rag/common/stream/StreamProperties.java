package com.argus.rag.common.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 流事件存储配置。
 *
 * <p>前缀 {@code argus.stream}。默认 key 前缀 {@code argus:stream:events}、
 * TTL 1 小时、停止信号通道 {@code argus:stream:stop}。</p>
 */
@ConfigurationProperties(prefix = "argus.stream")
public class StreamProperties {

    /** 存储 key 前缀。 */
    private String prefix = "argus:stream:events";

    /** 事件日志 TTL。 */
    private Duration ttl = Duration.ofHours(1);

    /** Redis Pub/Sub 停止信号通道。 */
    private String stopChannel = "argus:stream:stop";

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public String getStopChannel() {
        return stopChannel;
    }

    public void setStopChannel(String stopChannel) {
        this.stopChannel = stopChannel;
    }
}
