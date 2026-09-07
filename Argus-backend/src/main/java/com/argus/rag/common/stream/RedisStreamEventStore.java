package com.argus.rag.common.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis List 的流式事件存储实现。
 *
 * <p>参考 Go 版 {@code internal/stream/redis_manager.go}：
 * 写 {@code RPUSH + EXPIRE}，读 {@code LRANGE(key, fromOffset, -1)}。</p>
 */
@Component
public class RedisStreamEventStore implements StreamEventStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final StreamProperties properties;

    public RedisStreamEventStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            StreamProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String streamKey(String biz, Long sessionId, String streamId) {
        return properties.getPrefix() + ":" + biz + ":" + sessionId + ":" + streamId;
    }

    @Override
    public String streamKey(String biz, String streamId) {
        return properties.getPrefix() + ":" + biz + ":" + streamId;
    }

    @Override
    public void append(String streamKey, StreamEvent event) {
        String json = serialize(event);
        redisTemplate.opsForList().rightPush(streamKey, json);
        redisTemplate.expire(streamKey, properties.getTtl());
    }

    @Override
    public List<StreamEvent> read(String streamKey, long fromOffset) {
        List<String> jsonElements = redisTemplate.opsForList().range(streamKey, fromOffset, -1);
        if (jsonElements == null || jsonElements.isEmpty()) {
            return List.of();
        }
        return jsonElements.stream().map(this::deserialize).toList();
    }

    @Override
    public long nextOffset(long fromOffset, int eventCount) {
        return fromOffset + eventCount;
    }

    @Override
    public boolean exists(String streamKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(streamKey));
    }

    @Override
    public void delete(String streamKey) {
        redisTemplate.delete(streamKey);
    }

    private String serialize(StreamEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化流事件失败", e);
        }
    }

    private StreamEvent deserialize(String json) {
        try {
            return objectMapper.readValue(json, StreamEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化流事件失败", e);
        }
    }
}
