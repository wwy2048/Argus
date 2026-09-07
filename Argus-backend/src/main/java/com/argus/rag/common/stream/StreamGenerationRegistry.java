package com.argus.rag.common.stream;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 流式生成任务调度与取消注册表。
 *
 * <p>生成任务与 HTTP 请求解耦：请求响应返回后，生成在独立虚拟线程（Java 21）上继续，
 * 事件写入共享 Redis 而非进程内存，断连后任意节点可续传。</p>
 *
 * <p>{@code streamId -> cancelAction} 注册表用于"停止"时取消生成；
 * 配对 Redis Pub/Sub（{@link StreamStopService}）即可实现跨副本取消。</p>
 */
@Component
public class StreamGenerationRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamGenerationRegistry.class);

    private final Map<String, Runnable> cancelRegistry = new ConcurrentHashMap<>();
    private final ExecutorService generationExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 提交生成任务，并注册"中断线程"式的取消动作。
     *
     * @param streamId   流 ID
     * @param generation 生成任务（生产者，追加事件到 store）
     */
    public void submit(String streamId, Runnable generation) {
        Future<?> future = generationExecutor.submit(() -> {
            try {
                generation.run();
            } finally {
                cancelRegistry.remove(streamId);
            }
        });
        cancelRegistry.put(streamId, () -> future.cancel(true));
    }

    /**
     * 提交生成任务，并使用自定义取消动作（例如 dispose Reactor 订阅）。
     *
     * @param streamId   流 ID
     * @param generation 生成任务
     * @param cancel     自定义取消动作
     */
    public void submit(String streamId, Runnable generation, Runnable cancel) {
        generationExecutor.submit(() -> {
            try {
                generation.run();
            } finally {
                cancelRegistry.remove(streamId);
            }
        });
        cancelRegistry.put(streamId, cancel);
    }

    /** 取消指定流的生成任务。 */
    public void cancel(String streamId) {
        Runnable cancel = cancelRegistry.remove(streamId);
        if (cancel != null) {
            cancel.run();
        }
    }

    /** 供诊断：指定流是否仍在生成中。 */
    public boolean isGenerating(String streamId) {
        return cancelRegistry.containsKey(streamId);
    }

    @PreDestroy
    public void shutdown() {
        generationExecutor.shutdownNow();
    }
}
