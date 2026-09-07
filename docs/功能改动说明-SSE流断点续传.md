# 功能改动说明：SSE 流断点续传

> 本文档面向开发者，概述本次“SSE 断点续传”特性涉及的**全部改动文件**、
> 每处改动的**作用**，以及**如何验证**。设计细节见 [SSE 流断点续传设计.md](./SSE流断点续传设计.md)。

## 一句话描述

参考 Go 版 `internal/stream/*` 的 Redis 断点续传方案，为 Argus 的知识问答与 AI 助手
**两个 SSE 流式接口**加上了「事件落 Redis + 断线重放 + 主动停止」能力，支持多副本下“断线后任意节点可续传”。

## 一、后端新增（com.argus.rag.common.stream，共 9 个类）

| 文件 | 作用 |
|------|------|
| `StreamEvent.java` | 事件单元 `{type,data}`；`stop()` 快捷方法 |
| `StreamEventStore.java` | 存储抽象：streamKey/append/read/nextOffset/exists/delete |
| `RedisStreamEventStore.java` | Redis List 实现：`RPUSH+EXPIRE` 写、`LRANGE(key,offset,-1)` 读 |
| `StreamProperties.java` | 配置 `argus.stream`：prefix/ttl/stopChannel |
| `StreamConfig.java` | 注册配置、创建 Redis Pub/Sub 停止信号监听容器 |
| `StreamGenerationRegistry.java` | Java 21 虚拟线程提交生成任务，`streamId→cancel` 注册表 |
| `StreamStopService.java` | 写 `stop` 事件 + Pub/Sub 广播；监听停止通道并取消生成 |
| `StreamPoller.java` | 100ms 轮询 offset=0 增量读取，推 SSE，读到终止事件即完成 |
| `StreamTerminalEvents.java` | 判定终止事件：done/error/stop/record |

## 二、后端修改

### 1. `qa/controller/QaController.java`
- 新增 `POST /api/qa/stream-ask`：生成 streamId，**先写一条含 streamId 的 `stream` 元信息事件**，
  再把生成任务 `registry.submit` 到虚拟线程，最后 `poller.pump` 轮询推送。
- 新增 `GET /api/qa/stream-ask/resume?streamId=`：从 offset=0 重放。
- 新增 `POST /api/qa/stream-ask/stop`：写 `stop` 事件。
- **关键修复**：`doOnComplete` 中 `recordId == null` 时补写 `done`，否则轮询器永远无法终止。

### 2. `assistant/controller/AssistantChatController.java`
- 新增 `POST /api/assistant/chat/stream`、`GET /chat/stream/resume?sessionId=&streamId=`、
  `POST /chat/stream/stop` body `{sessionId,streamId}`。
- resume/stop 前调用 `requireOwnedSession` 校验会话归属。

### 3. `assistant/model/vo/chat/AssistantChatStreamEvent.java`
- record 新增 `streamId` 字段；提供 `start/delta/done/error` 工厂方法，每个事件都携带 streamId。

### 4. `assistant/service/AssistantService.java`
- 新增 `streamChat(...)` 重载：`start → delta... → done` 事件序列，每个事件含 streamId；
  生成完成/异常后写终止事件。

### 5. `assistant/service/AssistantConversationService.java`
- `requireOwnedSession` 由 private 改为 public，供 Controller 校验会话归属。

### 6. `pom.xml`
- 新增 Spring Data Redis starter，用于 Redis List + Pub/Sub。

### 7. `resources/application-dev.yml`
- 新增 `spring.data.redis` 与 `argus.stream`（prefix/ttl/stop-channel）配置。

### 8. 测试
- `QaControllerTest.java`、`HybridChunkRetrievalServiceTest.java` 补充/适配流式相关断言。

## 三、前端修改

### 1. `src/api/qa.ts`
- `QaStreamHandlers` 新增 `onStream(streamId)` 回调，用于捕获后端回传的流 ID。
- 抽取 `readQaSse()` 读取逻辑；新增 `resumeQaStream()`、`stopQaStream()`。
- `dispatchQaSseEvent` 新增 `case stream` 解析 `{streamId}`。

### 2. `src/api/assistant.ts`
- 抽取 `readAssistantSse()`；新增 `resumeAssistantStream()`、`stopAssistantStream()`。

### 3. `src/types/assistant.ts`
- `AssistantChatStreamEvent` 新增 `streamId` 字段。

### 4. `src/views/qa/QaView.vue`
- 新增流级状态：`qaStreamAbort / activeStreamId / qaResumeAttempted`。
- `handleAsk`：`streamedContent` 提升到函数作用域；catch 三分支：
  `AbortError`→STOPPED、网络断连→自动 `resumeQaStream` 从 0 重放、否则 REQUEST_FAILED。
- 新增 `handleStopAsk()`：先 abort 本地再调 `stopQaStream`。
- 模板给 `QaComposer` 加 `@stop`。

### 5. `src/views/assistant/AssistantView.vue`
- 新增状态：`activeStreamId / activeStreamSessionId / resumeAttempted`。
- `handleAsk` 断连时清空 `target.content` 后 `resumeAssistantStream` 从 0 重放。
- `onStreamEvent` 顶部捕获 `ev.streamId / ev.sessionId`。
- `abortStream`：先 `streamAbort.abort()`，再 `stopAssistantStream`。

### 6. `src/views/qa/components/QaComposer.vue`
- emits 新增 `stop`；loading 态按钮由“发送”切换为“停止”（`v-if="loading"`）；新增对应 CSS。

## 四、部署

- `deploy/server-a.compose.yml`：新增 `redis:7-alpine`（healthcheck `redis-cli ping`、`redis-data` volume）。
- `deploy/server-b.compose.yml`：backend 环境注入 `SPRING_DATA_REDIS_*` 与 `ARGUS_STREAM_*`。
- 关键：**多副本连同一 Redis**，断线后任意副本可续传。

## 五、文档

- `docs/SSE流断点续传设计.md`：设计动机、Redis 结构、事件协议、API 契约、配置部署。
- 本文件：改动清单与验证方式。

## 六、如何验证

### 后端
```bash
cd D:\project\Argus\Argus-backend
mvn clean compile
mvn test
```
> 说明：当前沙箱对 Argus 只读，直接 `mvn` 会因无法写 `target/` 而失败；
> 需在有写权限的环境（或本机）运行。历史验证为 23 tests 0 failures。

### 前端
```bash
cd D:\project\Argus\Argus-frontend
node_modules\.bin\vue-tsc.cmd --noEmit -p tsconfig.app.json
```
> 期望：仅剩 groups 组件既有的 `Object is possibly undefined` 报错，不应出现本次改动文件的新错误。

## 七、注意事项

- **自动 resume 触发条件**：当前仅在 `fetch` 抛错（传输层断连）时自动 resume；
  SSE 正常返回但未收终止事件时不触发。如需更稳可加“恢复”按钮。
- **幂等**：resume 从 0 重放，视图层需“清空后重建”内容，避免重复拼接。
- **TTL**：事件默认保留 1h，超时后无法续传。
