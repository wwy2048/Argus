package com.argus.rag.common.stream;

/**
 * 流式事件存储单元。
 *
 * <p>对应 SSE 的单条事件，{@code type} 为事件名称（如 start/delta/done/error/stop/token/citations），
 * {@code data} 为需原样回传的数据载荷。</p>
 *
 * <p>对于助手（assistant）场景，{@code data} 是 {@code AssistantChatStreamEvent} 的 JSON 序列化字符串；
 * 对于知识问答（qa）场景，{@code token} 事件的 {@code data} 是原始文本，其余事件为 JSON 字符串。</p>
 *
 * @param type 事件类型名称
 * @param data 事件载荷（字符串）
 */
public record StreamEvent(String type, String data) {

    public static StreamEvent of(String type, String data) {
        return new StreamEvent(type, data);
    }

    /** 终止事件：stop，用于全副本感知的停止信号。 */
    public static StreamEvent stop() {
        return new StreamEvent("stop", "{}");
    }
}
