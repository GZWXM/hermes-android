# GPT 评审报告（Pollinations - gpt-5.4-nano-2026-03-17）

以下评审只指出**真正的问题**；按严重程度给出改进建议。

---

## 1) SSE/流式解析设计不可靠（对 / 失败处理缺失）  
**严重程度：致命**

### 问题
- 你假设响应一定是类似 `reader.readUtf8Line()` 的“按行”SSE：  
  但很多 SSE 实现会在块边界/网络分片时让行不完整（`readUtf8Line()` 可能拿不到完整 `data:`）。
- 解析逻辑只处理单行 `data: ...`，但 SSE 规范允许**同一事件多行 data** 并以空行分隔事件；也可能出现 `event:`、`id:`、注释行 `: ping`。
- 也没有处理 HTTP 级别失败（例如 401/429/500）时 body 可能不是 SSE；当前会继续 while 读流，导致错误难以定位。
- `parseDelta(data)` 未定义：如果 Hermes/OpenAI 兼容字段略有差异（如 `choices[0].delta.content` 为空、或返回 `role/finish_reason` 等），会直接丢内容或崩。

### 改进建议
- 用**SSE 专用解析器**或至少实现“缓冲 + 按 SSE 事件（空行）提交”的逻辑：
  - 维护 `StringBuilder` 缓冲，按字节流读取，拼接到出现 `\n\n` 才解析事件。
  - 解析 `data:` 多行累计到同一个事件。
  - 跳过 `: ping` 注释行。
- 在进入 SSE 解析前必须检查：
  - `response.isSuccessful`，否则读取 error body 并映射成明确的 `StreamEvent.Error`。
- `StreamEvent.Done` 应由 SSE 事件中的 finish 标记驱动，不要只信 `[DONE]`（不同实现可能不用这个 token）。
- `parseDelta` 需要使用健壮的 JSON 解析（空值/缺字段容错），并对 `finish_reason`、`usage` 做落库或忽略策略。

---

## 2) Room/消息模型与“流式增量写入”策略未落地，存在数据一致性风险  
**严重程度：高**

### 问题
- 文档里说“每收到 delta 追加到内容 → 更新 UI → [DONE] 保存到 Room”。  
  但 Room 实体 `Message` 里 `content` 是不可变存储；你需要明确：
  1) SENDING 的 assistant message 是不是会先插入一条（status=SENDING）？id 如何生成？  
  2) delta 是不是只在内存里拼接，还是会更新 DB？
- 如果只在内存拼接到完成再写 DB：
  - App 崩溃/切后台杀进程会导致丢失 assistant 已返回但未完成的内容。
- 外键/索引：`messages` 有 `indices = [Index("conversationId")]`，但缺少按 `conversationId + timestamp` 的复合索引；聊天列表大量消息时会慢。

### 改进建议
- 明确采用一种一致性策略（建议：**增量落库 + 最终提交**）：
  - assistant message 创建时先插入（status=SENDING，content=""）。
  - 每隔 N 个 delta 或每 300ms（节流）更新该 message 的 content（避免写放大）。
  - 收到 finish 后更新 status=SENT，并更新 conversation 的 messageCount/lastMessagePreview。
- Room 查询建议加复合索引：
  - `Index(value=["conversationId","timestamp"])` 或用 `ORDER BY timestamp` 对应索引。
- 给 `status` 决定迁移规则：SENDING + app 重启时要么回放历史，要么标记 ERROR。

---

## 3) API 集成风险：请求/字段、流式协议与“OpenAI 兼容”假设过强  
**严重程度：高**

### 问题
- 你固定了 `model = "hermes-agent"`、并假设请求体与 OpenAI 一致且 Hermes 完全遵守：
  - `messages[].role/content`
  - SSE 输出 `choices[].delta.content`
- 没有在文档中列出 Hermes 对这些字段的差异点与兼容范围。
- `/v1/chat/completions` 的 SSE 格式你写死为特定片段；但实际可能：
  - 使用 `event: message`，而非纯 data 行
  - 最终不出现 `[DONE]`
  - usage / finish 结构不同

### 改进建议
- 在设计文档中补齐“**契约**”：
  - 至少给出从真实返回的 1-2 条样例（包含结束/错误场景）
  - 明确 delta/finish 的字段路径容错要求
- 建立集成测试：
  - 用 Espresso/Unit + MockWebServer 覆盖：分片、空行事件、多 data 行、非 200、超时、中途断开。
- 对 model、请求参数（temperature/max_tokens 等）不要写死：至少允许 Settings 配置或后续扩展。

---

## 4) OkHttp/超时与取消：流式场景下的取消/资源释放未说明  
**严重程度：中**

### 问题
- 读超时 120s 对长对话可能不足（尤其网络差时），而且你没有说明是否会在用户“停止生成”时取消调用。
- OkHttp response.body 的关闭、协程取消时停止读取未定义；否则可能出现：
  - 协程取消但读取循环仍在 blocking
  - 连接泄露

### 改进建议
- 在 `flow {}` 里使用 `awaitCancellation`/`currentCoroutineContext().isActive` 检查：
  - `while (isActive && !reader.exhausted()) { ... }`
- 退出时确保 `response.body?.close()`（try/finally）。
- 将超时拆分策略：
  - 连接超时 10-30s OK
  - 读超时对 SSE 建议更长或用更合理的“无数据超时”（需要拦截器/配置）
- 明确“停止生成按钮”行为：取消当前 Job，并让 OkHttp Call cancel。

---

## 5) 重试策略位置不对 + SSE 不适合无脑重试  
**严重程度：中**

### 问题
- 你写了 `RetryInterceptor` 放在拦截器链里，但没说明对 SSE 的重试策略。
- 对流式接口：
  - 中途断流可能已经产生部分输出，你重试会导致重复内容或消息错乱。

### 改进建议
- 对 `/v1/chat/completions`：
  - 明确禁止透明重试（或只在还未收到任何数据时重试一次）。
- 如果要重试：需要服务端支持幂等/断点续传（否则客户端只能“生成副本”，并在 UI 上处理为“重试版本”）。

---

## 6) Android 15(targetSdk 35) 特有陷阱：后台/通知/权限未覆盖  
**严重程度：中**

### 问题
- 文档未涉及 targetSdk 35 下的常见行为变化，至少要说明：
  - 如果未来要在后台生成/持续流式，Android 14/15 对后台执行更严格（需要 foreground service/Worker 的策略）。
  - 如果你计划“打字指示器/通知”，可能需要 POST_NOTIFICATIONS（Android 13+）以及 15 的相关收敛策略。
- 虽然当前文档是纯聊天，但你在 Phase 4 才上线，建议提前把这些依赖在架构上留接口（不要等爆炸）。

### 改进建议
- 在设计文档补充：
  - 流式任务与生命周期：ChatScreen 退出时是否 cancel？
  - 如果要跨后台继续：用 WorkManager/ForegroundService，并定义取消/重连策略。
- 权限与通知策略写清楚（即使当前不做通知，也建议在 Settings 明确不会使用）。

---

## 7) 数据容量估算粗糙：Message.content 可能远超 500 字节，Room 也需要清理策略  
**严重程度：低**

### 问题
- 估算按 500 字节/条过乐观；Markdown/代码块会显著增大。
- 没有“删除会话/清理旧消息/迁移策略”。

### 改进建议
- 添加：
  - 设置中可配置最大会话数/最大消息数
  - 或基于时间/大小的 LRU 清理
- Room 表增加 `createdAt` 查询与清理脚本/DAO。

---

# 按优先级的总结表

| 优先级 | 严重程度 | 问题点 | 核心风险 |
|---:|---|---|---|
| 1 | 致命 | SSE 解析基于 `readUtf8Line()` 且不符合 SSE 事件模型 | 丢内容/解析失败/线上难复现 |
| 2 | 高 | 流式 delta 与 Room 写入一致性/恢复策略未落地 | 崩溃丢生成内容/消息状态错乱 |
| 3 | 高 | OpenAI 兼容假设过强，缺少 Hermes 契约与测试样例 | 字段差异导致解析/展示错误 |
| 4 | 中 | OkHttp 流式取消/关闭与超时策略未说明 | 连接泄露、取消无效、卡死 |
| 5 | 中 | RetryInterceptor 对流式接口未定义策略 | 重试导致重复输出、对话错乱 |
| 6 | 中 | targetSdk 35 生命周期/后台执行/通知权限未覆盖 | 后期功能易受系统限制影响 |
| 7 | 低 | 容量估算与清理策略缺失 | 规模变大后性能/存储不可控 |

如果你愿意，我可以基于你现有结构给出一个“可直接落地”的 SSE 解析（缓冲按事件、容错 JSON、可取消）以及 Room 的增量写入节流策略代码骨架。

---
*模型: gpt-5.4-nano-2026-03-17 | tokens: {'completion_tokens': 2288, 'completion_tokens_details': {'accepted_prediction_tokens': 0, 'audio_tokens': 0, 'reasoning_tokens': 0, 'rejected_prediction_tokens': 0}, 'latency_checkpoint': {'engine_tbt_ms': 9, 'engine_ttft_ms': 109, 'engine_ttlt_ms': 20556, 'pre_inference_ms': 91, 'service_tbt_ms': 9, 'service_ttft_ms': 452, 'service_ttlt_ms': 20894, 'total_duration_ms': 20814, 'user_visible_ttft_ms': 361}, 'prompt_tokens': 3626, 'prompt_tokens_details': {'audio_tokens': 0, 'cached_tokens': 0}, 'total_tokens': 5914}*
