package com.argus.rag.common.stream;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE 轮询器：从 Redis 事件日志按 offset 增量拉取并推送到 {@link SseEmitter}。
 *
 * <p>Live 与 Resume 共用此逻辑，因此统一从 offset=0 开始读取（续传=重放 from 0）。
 * 读到终止事件（done/error/stop）即完成；客户端断连时停止轮询，生成继续不受影响。</p>
 */
@Component
public class StreamPoller {

    private static final Logger log = LoggerFactory.getLogger(StreamPoller.class);
    private static final long POLL_INTERVAL_MS = 100L;

    private final StreamEventStore store;
    private final ExecutorService pollerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public StreamPoller(StreamEventStore store) {
        this.store = store;
    }

    /** 建立轮询：从 offset=0 拉取并推送到 emitter。 */
    public void pump(SseEmitter emitter, String streamKey) {
        pollerExecutor.execute(() -> {
            long offset = 0L;
            try {
                while (true) {
                    List<StreamEvent> events = store.read(streamKey, offset);
                    if (events.isEmpty()) {
                        Thread.sleep(POLL_INTERVAL_MS);
                        continue;
                    }
                    offset = store.nextOffset(offset, events.size());
                    for (StreamEvent event : events) {
                        try {
                            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
                        } catch (IOException e) {
                            // 客户端断开：停止轮询，生成继续
                            return;
                        }
                        if (StreamTerminalEvents.isTerminal(event.type())) {
                            emitter.complete();
                            return;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("SSE 轮询异常结束: streamKey={}, offset={}", streamKey, offset, e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        pollerExecutor.shutdownNow();
    }
}
