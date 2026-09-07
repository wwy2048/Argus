package com.argus.rag.common.stream;

import java.util.List;

/**
 * 流式事件存储抽象。
 *
 * <p>使用 Redis List（append-only 事件日志）实现，key 形如
 * {@code argus:stream:events:<biz>:<sessionId>:<streamId>}（助手场景）或
 * {@code argus:stream:events:<biz>:<streamId>}（问答场景）。</p>
 *
 * <p>写：{@code RPUSH + EXPIRE}；读：{@code LRANGE(key, fromOffset, -1)}，
 * {@code nextOffset = fromOffset + 元素数}，从而实现游标式增量读取。
 * 这是"断线后任意节点可续传"的关键——事件写入共享 Redis 而非进程内存。</p>
 */
public interface StreamEventStore {

    /** 构造助手场景的存储 key。 */
    String streamKey(String biz, Long sessionId, String streamId);

    /** 构造问答场景的存储 key（仅需 streamId）。 */
    String streamKey(String biz, String streamId);

    /** 追加一条事件到指定 key。 */
    void append(String streamKey, StreamEvent event);

    /** 从 fromOffset 读取后续事件，空则返回空列表。 */
    List<StreamEvent> read(String streamKey, long fromOffset);

    /** 计算下一次读取的 offset。 */
    long nextOffset(long fromOffset, int eventCount);

    /** key 是否存在（即流是否已建立）。 */
    boolean exists(String streamKey);

    /** 删除整条流。 */
    void delete(String streamKey);
}
