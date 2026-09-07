# SSE 流断点续传设计（Java / Spring Boot 实现）

> 参考 Go 版 `internal/stream/*` 的 Redis 断点续传设计，在 Argus 中落地。
> 本文档说明设计动机、Redis 数据结构、事件协议、API 契约、配置与部署。

## 1. 背景与问题

SSE 流式接口（知识问答 `/api/qa/stream-ask`、AI 助手 `/api/assistant/chat/stream`）
通过一次 HTTP 连接持续推送 token。实际生产中有两个痛点：

- **客户端断连**：浏览器/网关抖动、用户切页、手机息屏等会导致 SSE 连接中断，
  此时大模型仍可能在生成，用户既看不到剩余内容，也无法简单“接着看”。
- **多副本部署**：后端可能跑在多个实例上。若事件只写进某实例的进程内存，
  该实例宕机或请求落到其它实例时，事件就丢了，无法续传。

## 2. 设计目标与取舍

- **断线后，任意节点可续传**：事件写入**共享 Redis**，而非进程内存。
  只要流还在 Redis 里（TTL 内），任何副本都能按 offset 重放。
- **可重放、随机位置续读**：事件是“日志”语义，客户端可随意从 offset=0 或任意位置重建内容。
- **用 Redis List + offset，而非 Redis Stream**：
  - 事件量可控（单次问答 token 数有限），`LRANGE O(N)` 足够；
  - 实现简单：`RPUSH + EXPIRE` 写入、`LRANGE(key, fromOffset, -1)` 读取，
    `nextOffset = fromOffset + 事件数`，天然是游标式增量读；
  - 不需要 Redis Stream 的消费组/ACK 等复杂语义，反而更贴合“可重放日志”。

## 3. 总体架构

```
客户端(浏览器)                    后端(任意副本)                    Redis
   |  POST /stream-ask               |                          |
   |-------------------------------->|  生成 streamId            |
   |                                 |  RPUSH stream事件+EXPIRE  |
   |                                 |------------------------->|
   | 虚拟线程后台继续生成             |  (stream:events:* key)    |
   |  SSE 事件逐个推送               |                          |
   |<--------------------------------|  LRANGE 增量轮询         |
   |                                 |------------------------->|
   |  客户端断连                     |                          |
   |                                 |  生成不受影响，事件继续写 |
   |  GET /stream-ask/resume?streamId|                          |
   |-------------------------------->|  LRANGE(0,-1) 全量重放   |
   |<--------------------------------|                          |
```

生成任务与 HTTP 请求**解耦**：请求返回后，生成在 Java 21 虚拟线程上继续，事件写入共享 Redis。
因此断连后只需按 streamId 重放，即可在**任意副本**上重建完整内容。

## 4. Redis 数据结构

### 4.1 Key 格式

| 场景 | Key | 说明 |
|------|-----|------|
| 知识问答 | `argus:stream:events:qa:<streamId>` | 仅需 streamId |
| AI 助手 | `argus:stream:events:assistant:<sessionId>:<streamId>` | 需 sessionId + streamId |

- 前缀默认 `argus:stream:events`，可用 `ARGUS_STREAM_PREFIX` / `argus.stream.prefix` 覆盖。
- Value 为 **Redis List**（append-only 事件日志），每个元素是一条 `StreamEvent` 的 JSON：
  `{"type":"token","data":"..."}`。

### 4.2 写入（Append）

- `RPUSH key event_json` 追加事件到列表尾。
- `EXPIRE key <ttl>` 设置过期（默认 1h），防止无限膨胀。
- 对应代码：`RedisStreamEventStore.append()`。

### 4.3 读取（Read / cursor）

- `LRANGE key fromOffset -1` 从指定 offset 读到列表尾。
- `nextOffset = fromOffset + 读取事件数`，作为下次增量读取的起点。
- offset 即 List 下标，客户端/轮询器据此持续补读，直到读到终止事件。
- 对应代码：`RedisStreamEventStore.read() / nextOffset()`。

### 4.4 停止信号

- Redis **Pub/Sub 通道** `argus:stream:stop`（默认），用于跨副本广播“取消生成”。
- `StreamStopService.notifyStop()` 同时做两件事：
  1. 往事件日志追加 `stop` 终止事件 → 所有副本的**轮询器**收到后关闭各自 SSE 连接；
  2. 向 Pub/Sub 通道发送 streamId → **生产副本**上的生成任务被取消（`registry.cancel`）。
- 停止通道可用 `ARGUS_STREAM_STOP_CHANNEL` / `argus.stream.stop-channel` 覆盖。

## 5. 核心组件（com.argus.rag.common.stream）

| 类 | 职责 |
|-----|------|
| `StreamEvent` | 事件单元 `{type,data}`；`stop()` 快捷方法 |
| `StreamEventStore` | 存储抽象：streamKey/append/read/nextOffset/exists/delete |
| `RedisStreamEventStore` | Redis List 实现（RPUSH+EXPIRE / LRANGE） |
| `StreamProperties` | 配置 `argus.stream`：prefix/ttl/stopChannel |
| `StreamConfig` | 注册配置、创建 Redis Pub/Sub 监听容器 |
| `StreamGenerationRegistry` | 虚拟线程提交生成任务 + streamId→取消动作注册表 |
| `StreamStopService` | 写 stop 事件 + Pub/Sub 广播；监听停止通道并取消生成 |
| `StreamPoller` | 100ms 轮询 offset=0 增量读取，推送到 SseEmitter，读到终止事件即完成 |
| `StreamTerminalEvents` | 判定终止事件：done/error/stop/record |

### 5.1 StreamPoller（live 与 resume 共用）

```
pump(emitter, streamKey):
  offset = 0
  循环：
    events = store.read(streamKey, offset)
    若空：sleep(100ms) 继续
    否则 offset = store.nextOffset(offset, events.size())
      对每个 event：emitter.send(...)
        若 emitter.send 抛 IOException（客户端断开）：直接 return（生成继续）
        若 event 是终止事件：emitter.complete(); return
```

Live 与 Resume **共用同一逻辑**，因此统一从 offset=0 开始读（续传＝重放 from 0）。
客户端断连只停轮询，**不中断生成**，为续传留了后路。

## 6. 事件协议（SSE）

### 6.1 知识问答 /api/qa/stream-ask

| 事件 | data | 含义 |
|------|------|------|
| `stream` | `{"streamId":"..."}` | 流元信息，客户端应记录（用于 resume/stop） |
| `token` | 文本片段 | 模型输出的增量文本 |
| `citations` | `[{...}]` | 引用来源列表 |
| `evidence-overview` | `{...}` | 证据概览（可选） |
| `record` | `{"recordId":1}` | 完成并落库，携带记录 ID（终止事件） |
| `error` | `{"message":"..."}` | 出错（终止事件） |
| `done` | `{}` | 完成（终止事件；仅无 recordId 时写入） |

### 6.2 AI 助手 /api/assistant/chat/stream

每个事件 data 为 `AssistantChatStreamEvent` 的 JSON（含 `streamId`/`sessionId`）：

| event | 字段 | 含义 |
|-------|------|------|
| `start` | streamId/sessionId/toolMode/groupId | 流开始（客户端借此捕获 streamId） |
| `delta` | delta 文本 | 增量文本 |
| `done` | messageId/reply/citations | 完成（终止事件） |
| `error` | error 消息 | 出错（终止事件） |

### 6.3 终止事件

`done`、`error`、`stop`、`record` 视为终止：轮询器读到即关闭 SSE 连接。
`StreamTerminalEvents.isTerminal(type)` 判定。

## 7. API 契约

| 场景 | 知识问答 | AI 助手 |
|------|---------|---------|
| 实时流 | `POST /api/qa/stream-ask` | `POST /api/assistant/chat/stream` |
| 断点续传 | `GET /api/qa/stream-ask/resume?streamId={}` | `GET /api/assistant/chat/stream/resume?sessionId={}&streamId={}` |
| 主动停止 | `POST /api/qa/stream-ask/stop` body `{streamId}` | `POST /api/assistant/chat/stream/stop` body `{sessionId,streamId}` |

- 新建流时，后端**先写入一条含 streamId 的元信息事件**，客户端据此记录。
- `/resume` 从 offset=0 重放该流全部已写事件；若已写入终止事件则立即结束，否则继续轮询直至终止。

## 8. 配置

### 8.1 application-dev.yml

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
argus:
  stream:
    prefix: ${ARGUS_STREAM_PREFIX:argus:stream:events}
    ttl: ${ARGUS_STREAM_TTL:1h}
    stop-channel: ${ARGUS_STREAM_STOP_CHANNEL:argus:stream:stop}
```

### 8.2 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `REDIS_HOST` | localhost | Redis 地址 |
| `REDIS_PORT` | 6379 | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `ARGUS_STREAM_PREFIX` | argus:stream:events | 事件 key 前缀 |
| `ARGUS_STREAM_TTL` | 1h | 事件日志 TTL |
| `ARGUS_STREAM_STOP_CHANNEL` | argus:stream:stop | 停止信号通道 |

## 9. 部署（多副本）

- `deploy/server-a.compose.yml`：新增 `redis:7-alpine`（含 healthcheck `redis-cli ping`、`redis-data` volume），
  供多副本共享事件日志。
- `deploy/server-b.compose.yml`：backend 注入上述 Redis/stream 环境变量。
- 关键：**所有副本连同一个 Redis**，断线后请求落到任一副本都能按 streamId 重放。

## 10. 前端接入

- `src/api/qa.ts`：`onStream` 回调捕获 streamId；`resumeQaStream` / `stopQaStream`。
- `src/api/assistant.ts`：`resumeAssistantStream` / `stopAssistantStream`。
- 视图层在 `handleAsk` 捕获断连后**自动从 0 重放**（AbortError 标记 STOPPED、网络断连走 resume、否则 FAILED）。
- 停止按钮先 abort 本地 SSE，再向后端写 `stop`。

## 11. 边界与限制

- **自动 resume 的触发条件**：当前仅在 `fetch` 抛错（传输层断连）时自动 resume。
  若 SSE 正常返回但未收到终止事件（异常关闭），不会自动触发。
  如需更稳，可加“恢复”按钮或 `onMounted` 检测残留 streamId。
- **TTL**：事件默认保留 1h，超时后无法续传。
- **幂等**：resume 从 0 重放，客户端应“清空后重建”文案 / 引用，避免重复拼接。

## 12. 与 Go 版对照

| Go | Java |
|----|------|
| `STREAM_MANAGER_TYPE=redis` 分流 | `StreamConfig` 默认 Redis |
| `AppendEvent`/`GetEvents` | `append()`/`read()` |
| `key=stream:events:<sessionID>:<messageID>` | `argus:stream:events:<biz>:...` |
| `ContinueStream` 全量重放/轮询 | `StreamPoller` + `/resume` |
| stop 事件广播 | `StreamStopService` + Pub/Sub |
