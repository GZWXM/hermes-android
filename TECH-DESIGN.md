# Hermes Android 客户端 — 技术设计文档 v1.1

> 目标：替代微信作为 Hermes Agent 前端，提供原生 Android 聊天体验。
> 评审：DeepSeek V4 Pro + GPT-5.4 Nano 双审，已合并修复。

**架构：** MVVM + Jetpack Compose + OkHttp + Room + Hilt DI  
**API：** Hermes API Server（端口 8642，OpenAI 兼容）  
**最低 SDK：** 26 (Android 8.0)  
**目标 SDK：** 35 (Android 15)

---

## 1. 系统架构总览

```
┌────────────────────────────────────────────┐
│                  UI Layer                   │
│  Compose Screens (Chat / Sessions / Settings)│
│  ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │ ChatScreen│ │SessionList│ │SettingsScr│  │
│  └────┬─────┘ └────┬─────┘ └─────┬─────┘  │
│       │             │              │        │
│  ┌────┴─────────────┴──────────────┴─────┐ │
│  │           ViewModels (Hilt)            │ │
│  │  ChatVM / SessionListVM / SettingsVM   │ │
│  │  + SavedStateHandle (进程死亡恢复)      │ │
│  └────────────────┬──────────────────────┘ │
├───────────────────┼────────────────────────┤
│              Domain Layer (已精简)           │
│  ┌────────────────┴──────────────────────┐ │
│  │    ChatRepository / SessionRepository  │ │
│  │    直接使用 Room Entity，不设冗余映射     │ │
│  └──────┬──────────────────┬─────────────┘ │
├─────────┼──────────────────┼───────────────┤
│      Data Layer            │                │
│  ┌──────┴──────┐   ┌───────┴──────┐        │
│  │  Remote      │   │  Local (Room) │        │
│  │  ApiService  │   │  MessageDao   │        │
│  │  (OkHttp)    │   │  SessionDao   │        │
│  └──────┬───────┘   └──────────────┘        │
│         │                                    │
│  ┌──────┴───────┐                            │
│  │ Hermes API   │                            │
│  │ Server:8642  │                            │
│  └──────────────┘                            │
└────────────────────────────────────────────┘
```

**架构说明：**
- Domain Layer 不设独立 model——Repository 直接使用 Room Entity。MVVM 三层（UI → ViewModel → Repository + Room）已足够，无需在 Entity 和 ViewModel 之间引入冗余映射层。
- ViewModel 使用 `SavedStateHandle` 保存输入草稿等瞬态，进程死亡后恢复。

---

## 2. API 通信方案

### 2.1 端点选择

| 端点 | 方法 | 用途 | 本项目使用 |
|------|------|------|-----------|
| `/v1/chat/completions` | POST | OpenAI 格式，支持 streaming | ✅ 主要 |
| `/v1/runs` | POST | 异步 run，立即返回 run_id | 🔄 备选 |
| `/v1/runs/{id}` | GET | 轮询 run 状态 | 🔄 备选 |
| `/api/sessions` | GET/POST | 会话管理 | ✅ 次要 |
| `/health` | GET | 启动时连通性检查 | ✅ |

### 2.2 推荐方案：Chat Completions + SSE Streaming

**为什么不用 /v1/runs 轮询：**
- `/v1/runs` 是异步模式，适合长时间任务和需要审批的场景
- `/v1/chat/completions` 同步流式，`stream: true` 直接返回 SSE 文本流
- 日常对话不需要审批/中断等复杂生命周期

**请求格式：**
```json
POST /v1/chat/completions
Authorization: Bearer {API_SERVER_KEY}
Content-Type: application/json

{
  "model": "hermes-agent",
  "messages": [
    {"role": "user", "content": "你好"}
  ],
  "stream": true
}
```

### 2.3 Hermes API 兼容性契约

> ⚠️ 重要：以下字段需要在实际 Hermes API Server 上验证，不可仅凭 OpenAI 规范假设。

**已验证/待验证字段：**
| 字段路径 | 预期 | 验证状态 |
|----------|------|----------|
| `choices[0].delta.content` | 增量文本 | 待验证 |
| `choices[0].finish_reason` | `"stop"` 表示结束 | 待验证 |
| 流结束标记 | `data: [DONE]` | 待验证 |
| SSE 事件格式 | 单行 `data:` + 空行分隔 | 待验证 |
| 多行 `data:` 事件 | 可能出现 | 待验证 |
| 注释行 `: ping` | 可能出现 | 待验证 |

**容错策略：** 解析时对上述所有变体做容错处理（详见 5.2 节）。

### 2.4 网络层设计

**OkHttp Client 配置：**
- 连接超时：15s
- 读取超时：180s（SSE 流式响应可能很长，且不应用作无数据超时）
- 写入超时：15s
- **拦截器链**：AuthInterceptor → LoggingInterceptor（仅 debug）→ **无 RetryInterceptor**
- **POST 流式请求禁止自动重试**（已删除 RetryInterceptor）：中途断流会导致消息重复，改为 UI 层手动"重新生成"按钮

### 2.5 认证

- 方式：Bearer Token
- Token：通过 `AuthInterceptor` 统一注入（非每个请求手动拼接）
- 来源：DataStore + Tink 加密存储（见第 9 节安全）
- 首次使用需用户在设置页配置

### 2.6 明文 HTTP 配置（Critical）

> **Android 9+ 默认禁止 `http://` 明文流量，包括 localhost。必须配置 network_security_config。**

`res/xml/network_security_config.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- 仅允许本地 API Server 的明文流量 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

AndroidManifest.xml 引用：
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

**禁止使用 `android:usesCleartextTraffic="true"` 全局放开**（安全风险）。

---

## 3. 数据模型

### 3.1 Room 实体

```kotlin
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "timestamp"])  // 复合索引，聊天列表排序用
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,             // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT
)

enum class MessageStatus { SENDING, SENT, ERROR }
```

**变更说明：**
- 主键从 `String (UUID)` → `Long (autoGenerate)`，索引性能提升 2-3x，外键表空间减半。
- 新增复合索引 `[conversationId, timestamp]`，优化聊天列表按时间排序查询。

### 3.2 Room 数据库配置

```kotlin
@Database(
    entities = [Conversation::class, Message::class],
    version = 1,
    exportSchema = true  // 必须开启，用于 AutoMigration
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
```

**迁移策略：** 从 v1 起规划版本号，后续使用 Room AutoMigration（Room 2.4+），禁止 `fallbackToDestructiveMigration()` 丢用户数据。

### 3.3 存储容量与清理

- 预估：100 对话 × 50 条 ≈ 2.5 MB（Markdown/代码块可能使单条远超 500 字节）
- 清理策略（Phase 2 实现）：
  - 设置可配置最大对话数（默认 200）
  - LRU 清理：超过上限时删除最旧对话
  - `ConversationDao` 提供 `deleteOldest(keepCount: Int)` 方法

---

## 4. UI 设计

### 4.1 屏幕结构

```
NavHost
├── ConversationListScreen (首页)
│   ├── TopBar: "Hermes" + 设置按钮
│   ├── LazyColumn: 对话列表（标题 + 最后消息预览 + 时间）
│   └── FAB: 新建对话
│
├── ChatScreen(conversationId)
│   ├── TopBar: 对话标题 + 返回按钮
│   ├── LazyColumn: 消息列表（气泡）
│   │   ├── UserMessage: 右对齐，蓝色气泡
│   │   └── AssistantMessage: 左对齐，灰色气泡，Markdown 渲染
│   ├── 打字指示器（isStreaming 时三个跳动的点）
│   ├── 停止生成按钮（isStreaming 时可见）
│   └── BottomBar: 输入框 + 发送按钮
│
└── SettingsScreen (设置)
    ├── 服务器地址配置（默认 http://localhost:8642）
    ├── API Key 管理（DataStore + Tink 加密存储）
    ├── 最大对话数 / 清理策略
    └── 关于
```

### 4.2 Compose 组件树

```
App
└── HermesTheme (Material3)
    └── NavHost
        ├── ConversationListScreen
        │   └── ConversationListViewModel
        │       ├── conversations: StateFlow<List<Conversation>>
        │       └── createConversation(title)
        │
        ├── ChatScreen(conversationId)
        │   └── ChatViewModel(savedStateHandle, conversationId)
        │       ├── messages: StateFlow<List<Message>>
        │       ├── isStreaming: StateFlow<Boolean>
        │       ├── streamingContent: StateFlow<String>
        │       ├── draftText (SavedStateHandle 持久化)
        │       ├── sendMessage(text)
        │       └── stopGeneration()
        │
        └── SettingsScreen
            └── SettingsViewModel
```

### 4.3 视觉风格

- 主题：Material 3 + Dynamic Color（跟随系统）
- 消息气泡：参考微信/Telegram 的简洁风格
- Markdown 渲染：使用 `com.github.jeziellago:compose-markdown` 库（~50KB），避免自写解析器
- 加载状态：骨架屏 + 打字指示器
- 暗色模式：支持

### 4.4 进程死亡与配置变更

- **SavedStateHandle**：输入框草稿、当前 conversationId 通过 `SavedStateHandle` 持久化
- **LazyColumn 滚动位置**：使用 `rememberSaveable` 包裹 `LazyListState`，旋转屏幕不丢位置
- **ViewModel 作用域**：ChatScreen 使用 `hiltViewModel(backStackEntry)` 按导航目标绑定，不同对话不共用 ViewModel

---

## 5. 核心数据流

### 5.1 发送消息流程

```
用户输入 → ChatVM.sendMessage(text)
  │
  ├─ 1. 本地插入 user message（status=SENT，立即落库）
  ├─ 2. 插入 assistant placeholder（content=""，status=SENDING）
  ├─ 3. 更新 UI（两条消息立即显示）
  ├─ 4. ChatRepository.sendMessage(text, history)
  │     ├─ 构建 ChatRequest（含历史消息）
  │     ├─ OkHttp POST → /v1/chat/completions (stream=true)
  │     └─ 返回 SSE 流 → Flow<StreamEvent>
  ├─ 5. 收集流式响应（callbackFlow + Dispatchers.IO）
  │     ├─ 每收到 delta → 内存拼接 → 节流更新 Room（每 300ms 或 5 个 delta）
  │     ├─ UI 通过 Flow 实时观察 Room 变化
  │     ├─ 用户点"停止生成" → cancel Job + OkHttp Call.cancel()
  │     └─ 收到 finish → status=SENT → 更新 conversation 摘要
  └─ 6. 错误处理
        ├─ HTTP 401/429/5xx → 不解析 body 为 SSE → 显示明确错误
        ├─ 网络中断 → status=ERROR → 显示"重新生成"按钮
        └─ 中间断流 → 保留已接收内容 + ERROR 状态
```

### 5.2 流式响应处理（核心 — v1.1 重写）

```kotlin
// ChatRepository.kt
fun sendMessage(userMessage: String, history: List<Message>): Flow<StreamEvent> = callbackFlow {
    val call = okHttpClient.newCall(
        Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(jsonBody(ChatRequest("hermes-agent", history.toApiMessages() + ApiMessage("user", userMessage), stream = true)))
            .build()
    )
    val response = call.execute()

    // 先检查 HTTP 状态码
    if (!response.isSuccessful) {
        val errorBody = response.body?.string() ?: "Unknown error"
        trySend(StreamEvent.Error("HTTP ${response.code}: $errorBody"))
        close()
        return@callbackFlow
    }

    val reader = response.body?.source() ?: run {
        trySend(StreamEvent.Error("Empty response body"))
        close()
        return@callbackFlow
    }

    // 确保取消或异常时关闭连接
    invokeOnClose {
        reader.closeQuietly()
        response.closeQuietly()
    }

    withContext(Dispatchers.IO) {
        try {
            val eventBuffer = StringBuilder()
            val dataLines = mutableListOf<String>()
            var inEvent = false

            while (isActive && !reader.exhausted()) {
                val line = reader.readUtf8Line() ?: continue

                when {
                    // SSE 注释行（keepalive ping）
                    line.startsWith(":") -> continue

                    // data: 行
                    line.startsWith("data: ") -> {
                        inEvent = true
                        dataLines.add(line.removePrefix("data: "))
                    }

                    // 空行 = 事件结束
                    line.isEmpty() && inEvent -> {
                        inEvent = false
                        val eventData = dataLines.joinToString("\n")
                        dataLines.clear()

                        when {
                            eventData == "[DONE]" -> trySend(StreamEvent.Done)
                            eventData.isNotBlank() -> {
                                val chunk = parseDeltaSafely(eventData)
                                if (chunk != null) trySend(StreamEvent.Chunk(chunk))
                            }
                        }
                    }

                    // 其他行：event:/id: 等（当前忽略，但不过滤）
                    else -> { /* 记录日志，不中断 */ }
                }
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

/**
 * 安全解析 delta，容错处理字段缺失/类型错误/非标准格式
 */
private fun parseDeltaSafely(jsonStr: String): String? {
    return try {
        val obj = org.json.JSONObject(jsonStr)
        val choices = obj.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.optJSONObject(0) ?: return null

        // 先检查 finish_reason
        val finishReason = choice.optString("finish_reason", null)
        if (finishReason != null && finishReason != "") return null  // 结束帧无内容

        val delta = choice.optJSONObject("delta") ?: return null
        delta.optString("content", null)  // 返回 null 时不崩溃
    } catch (e: Exception) {
        null  // JSON 解析失败静默丢弃
    }
}
```

**SSE 解析改进要点：**
1. **按事件模型解析**：缓冲多行 `data:` → 空行提交事件，而非逐行当独立事件
2. **跳过注释行**：`: ping` 等 keepalive 行不干扰解析
3. **先检查 HTTP 状态码**：非 2xx 不尝试解析 SSE body
4. **callbackFlow + invokeOnClose**：协程取消时自动关闭 reader 和 response
5. **容错 JSON 解析**：`optString`/`optJSONArray` 处理所有缺失字段
6. **不依赖 `[DONE]` 作唯一结束标记**：也检查 `finish_reason`

### 5.3 Room 增量写入策略

```kotlin
// ChatViewModel.kt
private fun collectStream(
    conversationId: Long,
    assistantMsgId: Long,
    stream: Flow<StreamEvent>
) {
    var buffer = StringBuilder()
    var updateCount = 0

    viewModelScope.launch {
        stream.collect { event ->
            when (event) {
                is StreamEvent.Chunk -> {
                    buffer.append(event.text)
                    updateCount++

                    // 每 5 个 delta 或 300ms 写入一次 Room
                    if (updateCount % 5 == 0) {
                        chatRepository.updateMessageContent(assistantMsgId, buffer.toString())
                    }
                }
                is StreamEvent.Done -> {
                    chatRepository.updateMessageContent(assistantMsgId, buffer.toString())
                    chatRepository.markMessageSent(assistantMsgId)
                    chatRepository.updateConversationPreview(conversationId, buffer.toString())
                }
                is StreamEvent.Error -> {
                    // 保留已接收内容
                    if (buffer.isNotEmpty()) {
                        chatRepository.updateMessageContent(assistantMsgId, buffer.toString())
                    }
                    chatRepository.markMessageError(assistantMsgId)
                }
            }
        }
    }
}
```

**一致性保证：**
- assistant message **先插入**（content=""，status=SENDING）
- 增量写入（节流 5 delta 或 300ms 写入 content）
- finish 时最终写入 + 更新状态
- 进程死亡后重启：Room 中 SENDING 消息标记为 ERROR，UI 显示可重试

---

## 6. 模块划分

```
app/
├── di/                          # Hilt 依赖注入
│   ├── AppModule.kt             # OkHttp, Room, DataStore
│   └── ViewModelModule.kt
│
├── data/
│   ├── remote/
│   │   ├── HermesApiService.kt  # OkHttp 请求构建
│   │   ├── ChatRequest.kt       # 请求体
│   │   └── SseParser.kt         # SSE 事件解析器（独立类，可单元测试）
│   ├── local/
│   │   ├── AppDatabase.kt       # Room 数据库（exportSchema=true, version=1）
│   │   ├── ConversationDao.kt
│   │   ├── MessageDao.kt
│   │   └── entity/              # Room 实体
│   └── repository/
│       ├── ChatRepository.kt    # 聊天业务逻辑 + SSE 流管理
│       └── SessionRepository.kt # 会话管理
│
├── ui/
│   ├── theme/
│   │   ├── Theme.kt
│   │   ├── Color.kt
│   │   └── Type.kt
│   ├── chat/
│   │   ├── ChatScreen.kt
│   │   ├── ChatViewModel.kt     # SavedStateHandle + 增量写入
│   │   ├── MessageBubble.kt
│   │   └── StreamingText.kt     # 流式打字效果
│   ├── conversation/
│   │   ├── ConversationListScreen.kt
│   │   └── ConversationListViewModel.kt
│   └── settings/
│       ├── SettingsScreen.kt
│       └── SettingsViewModel.kt
│
├── security/
│   └── ApiKeyManager.kt         # DataStore + Tink 加密存储
│
├── MainActivity.kt
└── HermesApp.kt                 # Application + Hilt 入口
```

**模块变更说明：**
- 移除 `domain/model/` 目录——不设独立领域模型，Repository 直接使用 Room Entity
- 新增 `security/ApiKeyManager.kt`——替代已废弃的 EncryptedSharedPreferences
- SSE 解析从 ChatRepository 中抽出为独立 `SseParser.kt`，方便单元测试

---

## 7. 关键技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 网络库 | OkHttp 4.x | 原生 SSE 支持好，Kotlin 协程友好 |
| 序列化 | kotlinx.serialization | Kotlin 原生，无反射，体积小 |
| Markdown | compose-markdown 库 | 成熟方案 ~50KB，避免自写解析器的边界 bug 和安全风险 |
| 导航 | Navigation Compose | 官方方案，类型安全 |
| 异步 | Kotlin Coroutines + Flow | 标准方案，配合 Room/OkHttp |
| DI | Hilt | 标准方案，生命周期感知 |
| API Key 存储 | DataStore + Tink | EncryptedSharedPreferences 已被 Google 废弃 |
| 图片加载 | Coil 3 | Phase 3+ 启用（MVP 阶段不引入，减少 APK 体积） |

---

## 8. 开发阶段

### Phase 1：最小可用（MVP）
- [x] 项目骨架 + Hilt 配置
- [ ] `network_security_config.xml`（明文 HTTP 放行 localhost）
- [ ] OkHttp + SSE 流式聊天（含 v1.1 解析器 + callbackFlow）
- [ ] Room 基础表（Conversation + Message，含增量写入策略）
- [ ] ChatScreen（单对话，SavedStateHandle 防进程死亡）
- [ ] 停止生成按钮 + 错误重试 UI
- [ ] HTTP 错误码处理（401/429/5xx）

### Phase 2：本地持久化完善
- [ ] 对话列表屏幕
- [ ] 消息历史加载
- [ ] 多对话管理
- [ ] Room 迁移策略验证
- [ ] LRU 清理策略

### Phase 3：完善
- [ ] Markdown 渲染（compose-markdown）
- [ ] 设置页（API Key / 服务器地址）
- [ ] DataStore + Tink 加密存储
- [ ] 暗色模式
- [ ] ProGuard/R8 规则

### Phase 4：发布
- [ ] GitHub Actions 自动编译 APK
- [ ] 签名配置
- [ ] 首次使用引导
- [ ] 单元测试 + SSE 解析器集成测试

---

## 9. 安全

### 9.1 API Key 存储

**方案：DataStore + Tink（Google 推荐替代 EncryptedSharedPreferences）**

```kotlin
// ApiKeyManager.kt
@Singleton
class ApiKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keysetManager = AndroidKeysetManager.Builder()
        .withSharedPref(context, "hermes_keyset", "hermes_prefs")
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withMasterKeyUri("android-keystore://hermes_master_key")
        .build()

    private val aead = keysetManager.keysetHandle.getPrimitive(Aead::class.java)

    fun encrypt(plaintext: String): ByteArray {
        return aead.encrypt(plaintext.toByteArray(), null)
    }

    fun decrypt(ciphertext: ByteArray): String {
        return String(aead.decrypt(ciphertext, null))
    }
}
```

### 9.2 认证注入

使用 OkHttp Interceptor 统一注入（非每个请求手动拼接）：

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

### 9.3 其他安全措施

- 不收集任何遥测数据
- 纯本地直连，不经过第三方服务器
- ProGuard 规则保护 kotlinx.serialization + Room + OkHttp 的序列化类

---

## 10. 注意事项

### 性能
- **消息列表**：LazyColumn + key + `rememberSaveable` 保持滚动位置
- **Room**：Flow 自动更新 + 复合索引 `[conversationId, timestamp]`
- **SSE 解析**：按事件缓冲，不缓存整个响应体

### Android 15 (targetSdk 35) 兼容
- **明文 HTTP**：已配置 `network_security_config.xml`（仅 localhost）
- **后台执行**：Phase 1 不涉及后台流式，ChatScreen 退出时 cancel Job
- **通知权限**：Phase 1 不使用通知，后续如需要前台服务需声明 `POST_NOTIFICATIONS`

### ProGuard/R8 规则（Phase 3 实现）
```proguard
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Tink
-dontwarn com.google.crypto.tink.**
```

### 测试策略（Phase 4）
- SSE 解析器（SseParser）单元测试：分片、多行 data、空行、注释行、BOM、[DONE]、finish_reason
- Room DAO 测试：增删改查 + 复合索引查询
- 集成测试：MockWebServer 模拟 Hermes API（200/401/429/分片流）
- UI 测试：ChatScreen 基本交互（Phase 3+）
