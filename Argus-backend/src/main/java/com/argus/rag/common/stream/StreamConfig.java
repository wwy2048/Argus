package com.argus.rag.common.stream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 流式事件存储相关配置。
 *
 * <p>注册 {@link StreamProperties}，并创建 Redis Pub/Sub 监听容器，
 * 订阅停止信号通道以跨副本取消生成任务。</p>
 */
@Configuration
@EnableConfigurationProperties(StreamProperties.class)
public class StreamConfig {

    @Bean
    public RedisMessageListenerContainer streamStopListenerContainer(
            RedisConnectionFactory connectionFactory,
            StreamStopService streamStopService,
            StreamProperties properties
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(streamStopService, new ChannelTopic(properties.getStopChannel()));
        return container;
    }
}
