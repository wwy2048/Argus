package com.argus.rag.common.stream;

import com.argus.rag.common.stream.StreamEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 停止信号服务。
 *
 * <p>写入 {@code stop} 终止事件到 Redis 事件日志（让所有副本的轮询器感知并关闭连接），
 * 同时通过 Redis Pub/Sub 通道广播 streamId，使生产副本上的生成任务被取消。</p>
 */
@Component
public class StreamStopService implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(StreamStopService.class);

    private final StreamEventStore store;
    private final StringRedisTemplate redisTemplate;
    private final StreamGenerationRegistry registry;
    private final StreamProperties properties;

    public StreamStopService(
            StreamEventStore store,
            StringRedisTemplate redisTemplate,
            StreamGenerationRegistry registry,
            StreamProperties properties
    ) {
        this.store = store;
        this.redisTemplate = redisTemplate;
        this.registry = registry;
        this.properties = properties;
    }

    /** 写入 stop 事件并广播取消信号。 */
    public void notifyStop(String streamKey, String streamId) {
        store.append(streamKey, StreamEvent.stop());
        redisTemplate.convertAndSend(properties.getStopChannel(), streamId);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String streamId = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("接收到停止信号，取消生成 streamId={}", streamId);
        registry.cancel(streamId);
    }
}
