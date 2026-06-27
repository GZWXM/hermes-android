# Hermes Android 客户端 — 技术设计评审报告

> 评审日期：2026-06-27
> 评审人：AI 技术评审
> 文档版本：当前 TECH-DESIGN.md

---

## 一、架构缺陷与遗漏

### 1.1 缺少进程死亡（Process Death）处理

**严重程度：高**

设计文档完全没有提及 Android 进程死亡场景。当用户切换到微信或其他 App 后返回，Android 可能在后台杀死进程。此时：
- ChatViewModel 中的所有内存状态（`streamingMessage`、未保存的消息、输入框内容）全部丢失
- 用户看到空白界面，已输入的文字消失，正在流式输出的 AI 回复中断且不可恢复

**建议：**
```kotlin
// ChatViewModel 应使用 SavedStateHandle
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
) : ViewModel() {
    // 输入框草稿应在进程死亡后恢复
    var draftText: String
        get() = savedStateHandle["draft"] ?: ""
        set(value) { savedStateHandle["draft"] = value }
}
```
同时，Room 持久化是唯一的救命稻草——但在 Phase 1 中 Room 尚未实现，MVP 阶段完全没有进程死亡恢复手段。建议将 Room 提前到 Phase 1，至少保证消息落地。

### 1.2 SSE 连接生命周期管理缺失

**严重程度：高**

设计中的 SSE 流式处理代码（第 247-277 行）存在严重遗漏：

1. **没有在 ViewModel.onCleared() 中取消 SSE 连接**：用户按返回键离开 ChatScreen 时，OkHttp 的 SSE 连接不会断开，服务端持续推送数据，造成资源泄漏
2. **没有使用 callbackFlow 包装**：直接用 `flow { }` builder 包裹阻塞式 `reader.readUtf8Line()` 是不安全的——它运行在调用者的协程上下文中，无法正确响应取消
3. **response.body 未关闭**：代码中没有任何地方调用 `response.close()` 或 `reader.close()`

**建议：**
```kotlin
fun sendMessage(userMessage: String, history: List<Message>): Flow<StreamEvent> = callbackFlow {
    val response = apiService.chatCompletions(...)
    val reader = response.body?.source() ?: run {
        close(IOException("Empty body"))
        return@callbackFlow
    }
    
    // 确保取消时关闭连接
    invokeOnClose {
        reader.close()
        response.close()
    }
    
    withContext(Dispatchers.IO) {
        try {
            while (!reader.exhausted() && isActive) {
                val line = reader.readUtf8Line() ?: continue
                // ... 解析 SSE
                trySend(event)
            }
        } catch (e: IOException) {
            trySend(StreamEvent.Error(e.message ?: "连接中断"))
        } finally {
            reader.closeQuietly()
            response.closeQuietly()
        }
    }
    awaitClose()
}.flowOn(Dispatchers.IO)
```

### 1.3 缺少离线/弱网队列机制

**严重程度：中**

文档第 394 行明确声明"不支持离线消息"，但对一个聊天客户端来说，即使用户在发送消息时网络断开，也应该：
- 将消息暂存到 Room（status=SENDING）
- 待网络恢复后自动重试
- 显示"发送失败，点击重试"UI

当前设计用户消息插入后立即发起网络请求，如果网络不通，用户消息显示为 SENDING 状态但没有重试入口（直到 Phase 3）。建议将此逻辑提前。

### 1.4 Domain Layer 有名无实

**严重程度：中**

文档第 27-29 行划分了 Domain Layer，第 310-313 行列出了 `domain/model/` 目录。但实际代码中：
- Repository 直接使用 Room Entity（第 32 行的 ChatRepository 引用 Room 的 MessageDao）
- ViewModel 也直接消费 Room Entity（第 203 行 `messages: StateFlow<List<Message>>`）
- Domain Model 和 Room Entity 完全相同（对比第 128 行和第 312 行）

这违反了 Clean Architecture 的依赖方向。要么删除 Domain Layer 简化架构（纯 MVVM 也够用），要么真正实现 entity → domain model 的映射。

### 1.5 导航时的 ViewModel 作用域问题

**严重程度：中**

设计中的 ChatViewModel 需要接收 `conversationId` 参数，但没有说明如何传递。使用 Navigation Compose + Hilt 时：
- 如果使用 `hiltViewModel()` 默认作用域，每次导航到 ChatScreen 会复用同一个 ViewModel（因为都绑定到同一个 NavBackStackEntry）
- 不同对话的消息会互相污染
- 需要使用 `SavedStateHandle` 获取导航参数，或使用 `hiltViewModel(backStackEntry)` 按导航目标作用域

---

## 二、API 集成风险

### 2.1 手动 SSE 解析的边界情况

**严重程度：高**

第 264 行 `reader.readUtf8Line()` 存在以下陷阱：

1. **长行截断**：SSE 的 `data:` 行可能非常长（如果服务端一次性推送大段 markdown），`readUtf8Line()` 在 Okio 中有内部缓冲区限制
2. **`[DONE]` 后可能还有数据**：第 267 行检查 `[DONE]` 后 `break`，但 SSE 协议规定 `[DONE]` 是一个独立的 data 行，不应该与其他内容混合
3. **空行处理**：SSE 使用空行分隔事件。当前逐行处理没有状态机概念，如果服务端在单个事件中发送多行 data（如 `data: line1\ndata: line2\n\n`），会被当作两个独立事件处理
4. **BOM 头**：SSE 响应可能以 UTF-8 BOM 开头，`readUtf8Line()` 不会自动剥离

**建议**：使用经过验证的 SSE 库，如：
- `com.launchdarkly:okhttp-eventsource:4.1.1`（专门为 Android 设计的 SSE 客户端，支持自动重连、背压）
- 或使用 Ktor Client 的 SSE 插件（`io.ktor:ktor-client-sse`），天然支持 Kotlin Flow 和协程取消

### 2.2 认证 Token 硬编码到请求中

**严重程度：中**

第 256 行将 `Bearer $apiKey` 直接拼接到每次请求中，但 `apiKey` 的来源和生命周期不明确：
- 如果 apiKey 发生变化（用户在设置页修改），正在进行的 SSE 流是否会使用旧 key？
- 没有 token 刷新机制（虽然当前 API 不需要，但设计应预留扩展点）

**建议**：使用 OkHttp Interceptor 统一注入认证头，而非每个请求手动拼接：
```kotlin
class AuthInterceptor(private val apiKeyProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${apiKeyProvider()}")
            .build()
        return chain.proceed(request)
    }
}
```

### 2.3 缺少 HTTP 错误处理

**严重程度：中**

SSE 流处理代码（第 247-277 行）完全没有检查 HTTP 状态码。如果服务端返回：
- `401 Unauthorized` — API Key 错误，当前代码会试图解析错误响应体为 SSE
- `429 Too Many Requests` — 应该等待 Retry-After 头
- `502/503` — 服务不可用，应该重试
- `5xx` 带 JSON 错误体 — 会被误解析为 SSE data 行

**建议**：在开始读取 SSE 流之前检查 `response.isSuccessful`，非 2xx 响应应解析 JSON 错误体并抛出明确异常。

### 2.4 请求缺少必要的 HTTP 头

**严重程度：低**

请求缺少：
- `Accept: text/event-stream` — 明确告知服务端期望 SSE 格式
- 没有处理 `Content-Type: text/event-stream` 的验证

### 2.5 POST 流式请求的重试问题

**严重程度：中**

文档第 119 行提到 `RetryInterceptor`，但 POST 请求的 SSE 流是不可重放的。如果 SSE 连接中途断开：
- 重试整个 POST 会创建新的聊天补全请求
- 服务端会生成全新的响应，造成消息重复
- 正确做法是丢弃已接收内容，在 UI 上显示错误并提供"重新生成"按钮（手动触发，而非自动重试）

---

## 三、更优替代方案

### 3.1 EncryptedSharedPreferences 已废弃

**严重程度：高**

文档第 382 行推荐使用 `EncryptedSharedPreferences` 存储 API Key。**但 Google 已在 AndroidX Security Crypto 库中正式废弃 EncryptedSharedPreferences**。Google 推荐迁移到：

- **DataStore + Tink**：Google 官方推荐方案，Tink 是 Google 维护的密码库，提供了 `AndroidKeysetManager` 与 Android Keystore 集成
- 或者直接使用 **Android Keystore + EncryptedFile** 存储敏感值

参考：[Goodbye EncryptedSharedPreferences: A 2026 Migration Guide](https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a)

### 3.2 UUID 作为 Room 主键的性能问题

**严重程度：中**

第 130/148 行使用 `UUID.randomUUID().toString()` 作为主键：
- 字符串主键比 `Long` 自增主键慢约 2-3 倍（索引查找、排序）
- UUID 占用 36 字节 vs Long 的 8 字节，主键还被外键表复制（Message 表每行多 28 字节开销）
- 对于纯本地数据库，没有分布式 ID 需求，自增 Long 是最优选择
- 如果确实需要全局唯一 ID（例如未来可能同步），可使用 ULID 或排序 UUID（UUID v7）减少索引碎片

### 3.3 自写 Markdown 解析器 vs 使用成熟库

**严重程度：中**

第 345 行声明"自写简易解析器"，理由是"只需代码块/粗体/链接"。但实际上：
- AI 输出可能包含嵌套列表、表格、引用块、嵌套格式（**粗体中的`代码`**）等
- 简单正则匹配会错误处理边界情况（如代码块中的 `**` 不应渲染为粗体）
- 安全风险：AI 输出中可能包含 XSS 向量（`[click](javascript:...)`），需要清理

**建议**：使用 `com.github.jeziellago:compose-markdown:0.5.3` 或类似的轻量库（~50KB），避免重复造轮子。或者至少有明确的转义/消毒策略。

### 3.4 网络层技术栈可简化

**严重程度：低**

当前方案：手动 OkHttp + 手动 SSE 解析 + kotlinx.serialization（仅用于请求体）

替代方案：Ktor Client
- `io.ktor:ktor-client-okhttp` + `io.ktor:ktor-client-sse` 提供原生 SSE 支持
- 内置 `kotlinx.serialization` 集成
- 原生 Flow 支持和协程取消
- 减少胶水代码量约 40%

但如果团队对 OkHttp 更熟悉，当前方案也可接受，前提是处理好上述 SSE 解析风险。

---

## 四、Android 特有陷阱

### 4.1 🔴 明文 HTTP 流量被阻止（最严重遗漏）

**严重程度：致命**

文档中 API 服务器使用的是 `http://localhost:8642`（基于端口号 8642 和架构图判断是 HTTP）。从 Android 9（API 28）起，**默认禁止所有明文 HTTP 流量**。应用 targetSdk 35 意味着：

- 所有 `http://` 请求会被系统直接拦截，抛出 `Cleartext HTTP traffic not permitted` 异常
- `localhost` / `127.0.0.1` 也不豁免（Android 14+ 尤其严格）

**必须做以下配置之一：**

**方案 A（推荐）**：在 `res/xml/network_security_config.xml` 中仅允许 localhost 的明文流量：
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- 仅允许本地开发服务器的明文流量 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

并在 AndroidManifest.xml 中引用：
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

**方案 B**：如果 Hermes API Server 支持 HTTPS（配置自签名证书），则配置信任自定义 CA。

⚠️ 注意：`android:usesCleartextTraffic="true"` 是全局放开，**不推荐**使用，除非仅用于开发调试。

### 4.2 Room 数据库没有迁移策略

**严重程度：中**

设计文档完全没有提到 Room 数据库版本管理和迁移。如果在后续 Phase 修改 Entity 结构：
- 使用 `fallbackToDestructiveMigration()` 会导致用户所有历史对话丢失
- 不使用则会在更新时崩溃

**建议**：
- 从第一个发布版本就规划数据库版本号（version = 1）
- 在 `@Database` 注解中声明 `exportSchema = true`
- 使用 Room 的 AutoMigration（Room 2.4+）减少手写 SQL

### 4.3 Room Entity 默认值问题

**严重程度：低**

```kotlin
val createdAt: Long = System.currentTimeMillis()
```

将 `System.currentTimeMillis()` 作为 Kotlin 默认值——这个默认值在编译时不会固定为编译时间，但在序列化/反序列化时可能产生意外行为。更重要的是：如果数据类是 `@Serializable` 的且与 API 通信共用，反序列化时缺失字段不会触发默认值（需要 `@EncodeDefault`）。

### 4.4 ProGuard/R8 混淆规则遗漏

**严重程度：中**

设计文档没有提及混淆规则。以下库需要特殊配置：

```proguard
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-dontwarn coil.**
```

### 4.5 前台服务缺失（长时间 SSE）

**严重程度：低**

SSE 流可能需要持续数分钟。如果用户在 AI 回复期间切换到其他 App（Home 键），Android 可能在几秒内暂停网络请求，甚至在几分钟后杀死进程。当前设计对此完全无保护。

**建议**：考虑在长时间流式响应期间启动低优先级前台服务（带通知 "AI 正在回复..."），或至少使用 `WorkManager` 的长时间任务支持。

### 4.6 屏幕旋转/配置变更

**严重程度：低**

文档提到使用 ViewModel（第 23 行），但没有提到如何处理配置变更（旋转、折叠屏展开/折叠）：
- ViewModel 默认存活于配置变更，但 LazyColumn 的滚动位置会丢失
- 如果使用 `rememberLazyListState()` 但没有用 `rememberSaveable`，用户旋转屏幕后消息列表回到顶部

---

## 五、其他遗漏点

### 5.1 无会话管理 API 的集成细节

文档列出 `/api/sessions` 端点为"次要"使用，但没有说明：
- Conversations 表和 Hermes 服务端的 sessions 如何关联
- 是否会同步服务端会话？
- 如果服务端已有会话历史，客户端如何加载？

### 5.2 聊天历史如何构建

第 251 行 `history.toApiMessages()` 没有详情：
- 上下文窗口管理策略？所有历史消息都发给 API？
- 对于长对话（100+ 条），token 限制如何处理？
- 是否需要服务端会话管理（以利用服务端的上下文窗口裁剪能力）？

### 5.3 健康检查端点未充分利用

`/health` 被标记为使用，但没有说明如何使用——是连接测试、周期性心跳、还是启动时验证？

### 5.4 缺少测试策略

整个设计文档没有任何关于测试的内容——单元测试、UI 测试、SSE 解析的测试、Room DAO 测试。

### 5.5 Image loading scenario unclear

文档提到使用 Coil 3 加载图片——但 MVP 阶段只有文本消息（第 392 行）。Coil 被过早引入，增加了 APK 体积但暂时无实际用途。可推迟到需要多媒体支持的阶段再引入。

---

## 六、总结与优先级

| 优先级 | 问题 | 影响 |
|--------|------|------|
| 🔴 P0 | 缺少 cleartext HTTP 配置 | 应用无法连接 API 服务器 |
| 🔴 P0 | SSE 连接未在 ViewModel 清除时关闭 | 资源泄漏，服务端压力 |
| 🔴 P0 | EncryptedSharedPreferences 已废弃 | 使用过时 API，未来可能无法编译 |
| 🟠 P1 | 手动 SSE 解析边界情况未处理 | 流式消息显示错乱或崩溃 |
| 🟠 P1 | 进程死亡无恢复机制 | 用户体验严重受损 |
| 🟠 P1 | POST 重试拦截器导致消息重复 | 数据一致性问题 |
| 🟠 P1 | 缺少 HTTP 错误状态码处理 | 401/429 等错误无用户反馈 |
| 🟡 P2 | UUID 主键性能问题 | 大规模数据时查询变慢 |
| 🟡 P2 | 自写 Markdown 解析器维护成本 | 边界情况多，安全风险 |
| 🟡 P2 | Domain Layer 形同虚设 | 架构理解混乱 |
| 🟡 P2 | Room 迁移策略缺失 | 未来更新丢数据 |
| 🟢 P3 | ProGuard 规则缺失 | Release 构建可能崩溃 |
| 🟢 P3 | 缺少测试策略 | 质量不可控 |
| 🟢 P3 | Coil 过早引入 | APK 体积浪费 |
