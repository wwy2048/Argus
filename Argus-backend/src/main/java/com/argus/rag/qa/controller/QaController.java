package com.argus.rag.qa.controller;

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
import com.argus.rag.qa.model.dto.AskQuestionRequest;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import com.argus.rag.qa.service.QaChatService;
import com.argus.rag.qa.service.QaService;
import com.argus.rag.qa.support.CitationAssembler;
import com.argus.rag.qa.support.EvidenceOverviewAssembler;
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
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识问答控制器。
 * <p>
 * 提供基于 RAG（检索增强生成）的知识库问答 API。
 * 用户在指定群组的知识库范围内提问，系统检索相关文档并由大模型生成回答。
 * </p>
 *
 * <p>流式接口支持断点续传：事件写入共享 Redis 事件日志，客户端断连后可通过 resume 重放。</p>
 */
@RestController
@RequestMapping("/api/qa")
@OperationLog
public class QaController {

    private static final Logger log = LoggerFactory.getLogger(QaController.class);

    /** SSE 连接超时时间（毫秒），默认 5 分钟 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final QaService qaService;
    private final CitationAssembler citationAssembler;
    private final EvidenceOverviewAssembler evidenceOverviewAssembler;
    private final StreamEventStore store;
    private final StreamPoller poller;
    private final StreamGenerationRegistry registry;
    private final StreamStopService streamStopService;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param qaService         知识问答服务
     * @param citationAssembler 引用组装器，用于流式回答完成后组装引用来源
     * @param evidenceOverviewAssembler 证据概览组装器
     * @param store             流式事件存储
     * @param poller            SSE 轮询器
     * @param registry          流式生成任务调度注册表
     * @param streamStopService 停止信号服务
     * @param objectMapper      JSON 序列化工具
     */
    public QaController(
            QaService qaService,
            CitationAssembler citationAssembler,
            EvidenceOverviewAssembler evidenceOverviewAssembler,
            StreamEventStore store,
            StreamPoller poller,
            StreamGenerationRegistry registry,
            StreamStopService streamStopService,
            ObjectMapper objectMapper) {
        this.qaService = qaService;
        this.citationAssembler = citationAssembler;
        this.evidenceOverviewAssembler = evidenceOverviewAssembler;
        this.store = store;
        this.poller = poller;
        this.registry = registry;
        this.streamStopService = streamStopService;
        this.objectMapper = objectMapper;
    }

    /**
     * 提问接口：在指定群组的知识库中检索并回答用户问题。
     *
     * @param askQuestionRequest 问答请求，包含群组 ID 和问题文本
     * @param request            HTTP 请求对象，用于提取当前用户身份信息
     * @return 问答响应，包含回答内容或拒答原因及引用来源
     */
    @PostMapping("/ask")
    public AskQuestionResponse askQuestion(
            @Valid @RequestBody AskQuestionRequest askQuestionRequest,
            HttpServletRequest request) {
        return qaService.ask(request, askQuestionRequest);
    }

    /**
     * 流式提问接口：使用 SSE 逐 token 推送大模型回答，支持断点续传。
     *
     * <h3>SSE 事件类型</h3>
     * <ul>
     *   <li><b>stream</b> — 流的元信息事件，data 为 {@code {"streamId":"..."}}，客户端应记录</li>
     *   <li><b>token</b> — 大模型生成的文本片段，客户端应拼接所有 token 得到完整回答</li>
     *   <li><b>citations</b> — 引用来源列表，在流式回答结束后发送</li>
     *   <li><b>evidence-overview</b> — 证据概览，在流式回答结束后发送</li>
     *   <li><b>record</b> — 持久化记录 ID，在流式回答结束后发送</li>
     *   <li><b>error</b> — 错误事件，包含 {@code message} 字段描述错误原因</li>
     * </ul>
     *
     * @param askQuestionRequest 问答请求，包含群组 ID 和问题文本
     * @param request            HTTP 请求对象，用于提取当前用户身份信息
     * @return SseEmitter 实例，用于推送 SSE 事件
     */
    @PostMapping("/stream-ask")
    public SseEmitter streamAsk(
            @Valid @RequestBody AskQuestionRequest askQuestionRequest,
            HttpServletRequest request) {
        // 请求级唯一标识：客户端记录后可续传。
        String streamId = UUID.randomUUID().toString();
        String streamKey = store.streamKey("qa", streamId);

        AuthenticatedUser authenticatedUser = UserContext.get();
        if (authenticatedUser == null) {
            throw new UnauthorizedException("当前请求未登录");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 先写入流的元信息事件，让客户端取得 streamId，便于断线后 resume / stop。
        store.append(streamKey, StreamEvent.of("stream", toJson(Map.of("streamId", streamId))));

        // 生成任务与 HTTP 解耦：订阅 token 流并把事件写入共享 Redis。
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable cancel = () -> {
            cancelled.set(true);
            Disposable d = disposableRef.get();
            if (d != null) {
                d.dispose();
            }
        };

        registry.submit(streamId, () -> {
            try {
                UserContext.set(authenticatedUser);
                QaChatService.StreamContext streamContext = qaService.askStream(request, askQuestionRequest);
                Disposable disposable = streamContext.tokenStream()
                        .doOnNext(token -> store.append(streamKey, StreamEvent.of("token", token)))
                        .doOnComplete(() -> {
                            try {
                                List<AskQuestionResponse.Citation> citations =
                                        citationAssembler.assembleDocuments(streamContext.documents());
                                if (!citations.isEmpty()) {
                                    store.append(streamKey, StreamEvent.of("citations", toJson(citations)));
                                }
                                AskQuestionResponse.EvidenceOverview evidenceOverview =
                                        evidenceOverviewAssembler.assemble(streamContext.documents());
                                if (evidenceOverview != null) {
                                    store.append(streamKey, StreamEvent.of("evidence-overview", toJson(evidenceOverview)));
                                }
                                Long recordId = streamContext.recordId();
                                if (recordId != null) {
                                    store.append(streamKey, StreamEvent.of("record", toJson(Map.of("recordId", recordId))));
                                } else {
                                    // 无 recordId 时也写入终止事件，否则轮询器永远无法 complete。
                                    store.append(streamKey, StreamEvent.of("done", "{}"));
                                }
                            } catch (Exception ex) {
                                log.error("流式问答完成事件组装失败: streamId={}", streamId, ex);
                                store.append(streamKey, StreamEvent.of("error", toJson(Map.of("message", ex.getMessage()))));
                            }
                        })
                        .doOnError(error -> {
                            String message = error.getMessage() != null
                                    ? error.getMessage()
                                    : "流式问答服务内部错误";
                            log.warn("流式问答 token 流异常: streamId={}", streamId, error);
                            store.append(streamKey, StreamEvent.of("error", toJson(Map.of("message", message))));
                        })
                        .subscribe();
                disposableRef.set(disposable);
                if (cancelled.get()) {
                    disposable.dispose();
                }
            } catch (Exception e) {
                // 同步阶段异常（如权限校验失败）
                String message = e.getMessage() != null ? e.getMessage() : "请求处理失败";
                log.error("流式问答初始化失败: streamId={}", streamId, e);
                store.append(streamKey, StreamEvent.of("error", toJson(Map.of("message", message))));
            } finally {
                UserContext.clear();
            }
        }, cancel);

        // 从 offset=0 轮询并推送（live 与 resume 共用同一逻辑）。
        poller.pump(emitter, streamKey);
        return emitter;
    }

    /**
     * 断点续传（SSE）。
     *
     * @param streamId 流 ID（原请求返回的 streamId）
     * @return SSE 发射器
     */
    @GetMapping(path = "/stream-ask/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeQaStream(@RequestParam String streamId) {
        String streamKey = store.streamKey("qa", streamId);
        if (!store.exists(streamKey)) {
            throw new BusinessException("流式请求不存在或已过期");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        poller.pump(emitter, streamKey);
        return emitter;
    }

    /**
     * 主动停止流式生成。
     *
     * @param body 停止请求体（streamId）
     * @return 统一响应
     */
    @PostMapping("/stream-ask/stop")
    @OperationLog
    public ApiResponse<Void> stopQaStream(@Valid @RequestBody StopStreamRequest body) {
        String streamKey = store.streamKey("qa", body.streamId());
        streamStopService.notifyStop(streamKey, body.streamId());
        return ApiResponse.success(null);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化流事件失败", e);
        }
    }

    /** 停止流式请求体。 */
    public record StopStreamRequest(String streamId) {
    }
}
