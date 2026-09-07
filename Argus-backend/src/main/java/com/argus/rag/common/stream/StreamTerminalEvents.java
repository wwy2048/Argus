package com.argus.rag.common.stream;

/**
 * 流式终止事件判定工具。
 *
 * <p>轮询器（poller）读到这些事件类型时应视为流结束并关闭 SSE 连接。</p>
 *
 * <p>终止事件包括：{@code done}（助手完成）、{@code error}（异常）、
 * {@code stop}（用户/系统主动停止）、{@code record}（知识问答完成并回传记录 ID）。</p>
 */
public final class StreamTerminalEvents {

    private StreamTerminalEvents() {
    }

    /** 判定 {@code type} 是否为终止事件。 */
    public static boolean isTerminal(String type) {
        return "done".equals(type) || "error".equals(type) || "stop".equals(type) || "record".equals(type);
    }
}