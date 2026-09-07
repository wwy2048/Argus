package com.argus.rag.assistant.controller;

import com.argus.rag.assistant.model.dto.chat.AssistantChatRequest;
import com.argus.rag.assistant.model.vo.chat.AssistantChatResponse;
import com.argus.rag.assistant.model.vo.chat.AssistantChatStreamEvent;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.service.AssistantService;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.common.log.OperationLog;
import com.argus.rag.common.security.AuthenticatedUser;
import com.argus.rag.common.security.UserContext;
import com.argus.rag.common.stream.StreamEvent;
import com.argus.rag.common.stream.StreamEventStore;
import com.argus.rag.common.stream.StreamGenerationRegistry;
import com.argus.rag.common.stream.StreamPoller;
import com.argus.rag.common.stream.StreamStopService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * 助手聊天控制器。
 * <p>提供同步聊天和流式聊天（SSE）的 RESTful 接口。</p>
 * <ul>
 *   <li>POST /api/assistant/chat - 同步聊天，一次请求完成对话</li>
 *   <li>POST /api/assistant/chat/stream - 流式聊天，通过 SSE 逐段推送回复内容</li>
 *   <li>GET /api/assistant/chat/stream/resume - 断点续传：重放该流的全部事件（from 0）</li>
 *   <li>POST /api/assistant/chat/stream/stop - 主动停止：写入 stop 事件并广播取消</li>
 * </ul>
 *
 * <p>生成任务与 HTTP 请求解耦：请求建立 SSE 通道后立即返回，生成在虚拟线程上继续，
 * 事件写入共享 Redis（多副本均可续传），客户端断连后可通过 resume 重放。</p>
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantChatController {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatController.class);
    private static final long SSE_TIMEOUT_MILLIS = 0L;

    private final AssistantService assistantService;
    private final AssistantConversationService assistantConversationService;
    private final CurrentUserService currentUserService;
    private final StreamEventStore store;
    private final StreamPoller poller;
    private final StreamGenerationRegistry registry;
    private final StreamStopService streamStopService;
    private final ObjectMapper objectMapper;

    public AssistantChatController(
            AssistantService assistantService,
            AssistantConversationService assistantConversationService,
            CurrentUserService currentUserService,
            StreamEventStore store,
            StreamPoller poller,
            StreamGenerationRegistry registry,
            StreamStopService streamStopService,
            ObjectMapper objectMapper
    ) {
        this.assistantService = assistantService;
        this.assistantConversationService = assistantConversationService;
        this.currentUserService = currentUserService;
        this.store = store;
        this.poller = poller;
        this.registry = registry;
        this.streamStopService = streamStopService;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步聊天。
     * <p>接收用户消息，同步调用助手服务完成对话，一次性返回完整的回复和引用列表。</p>
     *
     * @param requestBody 聊天请求体，包含会话 ID、工具模式、知识库组 ID 和用户消息
     * @param request     HTTP 请求对象，用于身份认证
     * @return 包含助手回复内容和引用列表的响应
     */
    @PostMapping("/chat")
    @OperationLog
    public ApiResponse<AssistantChatResponse> chat(
            @Valid @RequestBody AssistantChatRequest requestBody,
            HttpServletRequest request
    ) {
        return ApiResponse.success(assistantService.chat(request, requestBody));
    }

    /**
     * 流式聊天（SSE），支持断点续传。
     *
     * <p>建立 SSE 连接，生成请求级 {@code streamId}，把生成任务提交到虚拟线程执行；
     * 事件写入共享 Redis 事件日志，由轮询器逐段推送给客户端。客户端断连后生成继续，
     * 可通过 resume 接口从 offset=0 重放。</p>
     *
     * @param requestBody 聊天请求体
     * @param request     HTTP 请求对象，用于身份认证
     * @return SSE 发射器
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @OperationLog
    public SseEmitter streamChat(
            @Valid @RequestBody AssistantChatRequest requestBody,
            HttpServletRequest request
    ) {
        AuthenticatedUser authenticatedUser = UserContext.get();
        if (authenticatedUser == null) {
            throw new UnauthorizedException("当前请求未登录");
        }
        // 请求级唯一标识：客户端记录后可在此流或其它副本上续传。
        String streamId = UUID.randomUUID().toString();
        String streamKey = store.streamKey("assistant", requestBody.sessionId(), streamId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        // 生成任务与 HTTP 解耦：请求返回后生成在虚拟线程继续，事件写入 Redis。
        registry.submit(streamId, () -> {
            try {
                UserContext.set(authenticatedUser);
                assistantService.streamChat(
                        streamId,
                        request,
                        requestBody,
                        event -> store.append(streamKey, StreamEvent.of(event.event(), toJson(event)))
                );
            } catch (Exception e) {
                // 生成失败时写入 error 事件，让所有副本的轮询器感知并关闭连接。
                log.warn("SSE 流式生成异常: streamId={}", streamId, e);
                store.append(streamKey, StreamEvent.of("error", toJson(AssistantChatStreamEvent.error(
                        streamId,
                        requestBody.sessionId(),
                        requestBody.toolMode(),
                        requestBody.groupId(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                ))));
            } finally {
                UserContext.clear();
            }
        });

        // 从 offset=0 轮询并推送（live 与 resume 共用同一逻辑）。
        poller.pump(emitter, streamKey);
        return emitter;
    }

    /**
     * 断点续传（SSE）。
     *
     * <p>客户端在某个流断线后重新连接，从 offset=0 重放该流的全部事件；
     * 若流已写入终止事件则立即完成，否则继续轮询直至终止。</p>
     *
     * @param sessionId 会话 ID
     * @param streamId  流 ID（原请求返回的 streamId）
     * @param request   HTTP 请求对象，用于身份认证
     * @return SSE 发射器
     */
    @GetMapping(path = "/chat/stream/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeChatStream(
            @RequestParam Long sessionId,
            @RequestParam String streamId,
            HttpServletRequest request
    ) {
        requireOwnedSession(sessionId);
        String streamKey = store.streamKey("assistant", sessionId, streamId);
        if (!store.exists(streamKey)) {
            throw new BusinessException("流式请求不存在或已过期");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        poller.pump(emitter, streamKey);
        return emitter;
    }

    /**
     * 主动停止流式生成。
     *
     * <p>写入 stop 终止事件（让所有副本的轮询器关闭连接），并广播 streamId 取消生成任务。</p>
     *
     * @param body 停止请求体（sessionId + streamId）
     * @return 统一响应
     */
    @PostMapping("/chat/stream/stop")
    @OperationLog
    public ApiResponse<Void> stopChatStream(
            @Valid @RequestBody StopStreamRequest body
    ) {
        requireOwnedSession(body.sessionId());
        String streamKey = store.streamKey("assistant", body.sessionId(), body.streamId());
        streamStopService.notifyStop(streamKey, body.streamId());
        return ApiResponse.success(null);
    }

    /** 校验当前用户对该会话的归属权。 */
    private void requireOwnedSession(Long sessionId) {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireBusinessUser();
        assistantConversationService.requireOwnedSession(currentUser.userId(), sessionId);
    }

    private String toJson(AssistantChatStreamEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化流事件失败", e);
        }
    }

    /** 停止流式请求体。 */
    public record StopStreamRequest(Long sessionId, String streamId) {
    }
}
