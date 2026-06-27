# Hermes Android MVP — 极简可跑版（含图片）

> 目标：24h 内跑通第一条消息。不做架构，只做闭环。

**技术栈：** Kotlin + Compose + OkHttp + Room + Coil + ViewModel（无 Hilt，手动 DI）  
**最低 SDK：** 26 | **目标 SDK：** 35

---

## 1. 目录结构

```
app/
├── MainActivity.kt
├── data/
│   ├── AppDatabase.kt        # 一张表，version=1
│   ├── MessageDao.kt
│   ├── MessageEntity.kt
│   ├── HermesClient.kt       # OkHttp + SSE 流
│   └── SseParser.kt          # 独立可测
├── ui/
│   ├── ChatScreen.kt
│   └── ChatViewModel.kt
└── res/xml/
    └── network_security_config.xml
```

---

## 2. 数据层

### MessageEntity.kt

```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,           // "user" | "assistant"
    val content: String,        // 文本或 JSON 数组（含 image_url）
    val imageBase64: String?,   // null = 纯文本消息
    val timestamp: Long = System.currentTimeMillis()
)
```

### MessageDao.kt

```kotlin
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
```

### AppDatabase.kt

```kotlin
@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
}
```

---

## 3. 网络层

### HermesClient.kt

```kotlin
class HermesClient(
    private val baseUrl: String,
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .build())
        }
        .build()

    // messagesJson 已经是 JSONArray 字符串（含 image_url 的 content 数组）
    fun chatStream(messagesJson: String): Response {
        val body = """
            {
                "model": "hermes-agent",
                "messages": $messagesJson,
                "stream": true
            }
        """.trimIndent()

        return client.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
    }
}
```

### SseParser.kt

与 v1 相同，无变化。

—— SseParser 代码略（同上一版，独立提取 delta.content）——

```kotlin
object SseParser {
    fun parse(
        responseBody: ResponseBody,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val source = responseBody.source()
        try {
            val dataLines = mutableListOf<String>()
            var inEvent = false

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                when {
                    line.startsWith(":") -> continue
                    line.startsWith("data: ") -> {
                        inEvent = true
                        dataLines.add(line.removePrefix("data: "))
                    }
                    line.isEmpty() && inEvent -> {
                        inEvent = false
                        val data = dataLines.joinToString("\n")
                        dataLines.clear()
                        when {
                            data == "[DONE]" -> { onDone(); return }
                            data.isNotBlank() -> {
                                extractContent(data)?.let { onDelta(it) }
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            onError(e.message ?: "连接中断")
        } finally {
            source.closeQuietly()
            responseBody.closeQuietly()
        }
    }

    private fun extractContent(json: String): String? {
        return try {
            val obj = JSONObject(json)
            val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return null
            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) return null
            choice.optJSONObject("delta")?.optString("content", null)
        } catch (e: Exception) { null }
    }
}
```

---

## 4. ViewModel

```kotlin
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(application, AppDatabase::class.java, "hermes.db").build()
    private val dao = db.messageDao()
    private val client = HermesClient("http://127.0.0.1:8642", "YOUR_KEY")

    val messages: StateFlow<List<MessageEntity>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private var currentJob: Job? = null

    /**
     * @param text   文本内容（可为空，纯图片消息时为空）
     * @param imageBase64 可选图片 base64（不含 data:image/...;base64, 前缀）
     * @param mimeType    图片 MIME，如 "image/jpeg"
     */
    fun sendMessage(text: String, imageBase64: String? = null, mimeType: String = "image/jpeg") {
        viewModelScope.launch {
            // 1. 插入用户消息
            val displayText = if (imageBase64 != null && text.isBlank()) "[图片]" else text
            dao.insert(MessageEntity(
                role = "user",
                content = displayText,
                imageBase64 = imageBase64
            ))

            // 2. 插入空 assistant 占位
            val assistantId = dao.insert(MessageEntity(role = "assistant", content = "", imageBase64 = null))

            _isStreaming.value = true
            _streamingContent.value = ""

            currentJob = launch(Dispatchers.IO) {
                val historyJson = buildHistoryJson(text, imageBase64, mimeType)
                val response = client.chatStream(historyJson)

                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code}: ${response.body?.string()}"
                    dao.update(MessageEntity(id = assistantId, role = "assistant", content = "[错误] $errorMsg"))
                    _isStreaming.value = false
                    return@launch
                }

                val contentBuffer = StringBuilder()
                var updateCount = 0

                SseParser.parse(
                    responseBody = response.body!!,
                    onDelta = { delta ->
                        contentBuffer.append(delta)
                        updateCount++
                        _streamingContent.value = contentBuffer.toString()
                        if (updateCount % 10 == 0) {
                            dao.update(MessageEntity(id = assistantId, role = "assistant", content = contentBuffer.toString()))
                        }
                    },
                    onDone = {
                        dao.update(MessageEntity(id = assistantId, role = "assistant", content = contentBuffer.toString()))
                        _streamingContent.value = ""
                        _isStreaming.value = false
                    },
                    onError = { err ->
                        val partial = contentBuffer.toString().ifEmpty { "[错误] $err" }
                        dao.update(MessageEntity(id = assistantId, role = "assistant", content = partial))
                        _streamingContent.value = ""
                        _isStreaming.value = false
                    }
                )
            }
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        _isStreaming.value = false
    }

    private suspend fun buildHistoryJson(
        currentText: String,
        currentImageBase64: String?,
        currentMimeType: String
    ): String {
        val history = dao.getAll().first().takeLast(50)
        val array = JSONArray()

        for (msg in history) {
            val obj = JSONObject()
            obj.put("role", msg.role)

            // 重建原始 content 格式
            if (msg.imageBase64 != null) {
                // 图片消息 → content 是数组
                val parts = JSONArray()
                if (msg.content.isNotBlank() && msg.content != "[图片]") {
                    parts.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                parts.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                    })
                })
                obj.put("content", parts)
            } else {
                obj.put("content", msg.content)
            }
            array.put(obj)
        }

        // 追加当前用户消息
        val userObj = JSONObject()
        userObj.put("role", "user")
        if (currentImageBase64 != null) {
            val parts = JSONArray()
            if (currentText.isNotBlank()) {
                parts.put(JSONObject().apply {
                    put("type", "text")
                    put("text", currentText)
                })
            }
            parts.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:$currentMimeType;base64,$currentImageBase64")
                })
            })
            userObj.put("content", parts)
        } else {
            userObj.put("content", currentText)
        }
        array.put(userObj)

        return array.toString()
    }
}
```

---

## 5. UI

### ChatScreen.kt

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 图片选择
    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMime by remember { mutableStateOf("image/jpeg") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            // 在后台线程做 base64 编码
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                val context = LocalContext.current  // 注意：不能在 Composable 外直接用
            }
        }
    }

    // ⚠️ 上面那个 launcher 不能在 Dispatchers.IO 用 LocalContext
    // 正确做法见下方"修正版" ChatScreen

    // 自动滚到底部
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 消息列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (streamingContent.isNotEmpty()) {
                item(key = "streaming") {
                    Text(text = streamingContent, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // 已选图片预览
        selectedImageUri?.let { uri ->
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "已选图片",
                    modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(8.dp))
                Text("图片已附加", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    selectedImageUri = null
                    selectedImageBase64 = null
                }) {
                    Icon(Icons.Default.Close, "移除")
                }
            }
        }

        // 输入栏
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图片按钮
            IconButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !isStreaming
            ) {
                Icon(Icons.Default.Add, "添加图片")
            }

            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") },
                enabled = !isStreaming
            )

            if (isStreaming) {
                IconButton(onClick = { viewModel.stopGeneration() }) {
                    Icon(Icons.Default.Stop, "停止")
                }
            } else {
                IconButton(
                    onClick = {
                        if (input.isNotBlank() || selectedImageBase64 != null) {
                            viewModel.sendMessage(input, selectedImageBase64, selectedImageMime)
                            input = ""
                            selectedImageUri = null
                            selectedImageBase64 = null
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, "发送")
                }
            }
        }
    }
}
```

### ChatScreen.kt（修正版 —— 解决 context 跨线程问题）

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 图片选择
    var selectedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
    var selectedImageMime by remember { mutableStateOf("image/jpeg") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            scope.launch(Dispatchers.IO) {
                val mime = context.contentResolver.getType(it) ?: "image/jpeg"
                selectedImageMime = mime
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                selectedImageBase64 = bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            }
        }
    }

    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (streamingContent.isNotEmpty()) {
                item(key = "streaming") {
                    Text(text = streamingContent, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // 图片预览
        selectedImageUri?.let { uri ->
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(8.dp))
                Text("图片已附加", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    selectedImageUri = null
                    selectedImageBase64 = null
                }) {
                    Icon(Icons.Default.Close, "移除")
                }
            }
        }

        // 输入栏
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !isStreaming
            ) {
                Icon(Icons.Default.Add, "添加图片")
            }

            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") },
                enabled = !isStreaming
            )

            if (isStreaming) {
                IconButton(onClick = { viewModel.stopGeneration() }) {
                    Icon(Icons.Default.Stop, "停止")
                }
            } else {
                IconButton(onClick = {
                    if (input.isNotBlank() || selectedImageBase64 != null) {
                        viewModel.sendMessage(input, selectedImageBase64, selectedImageMime)
                        input = ""
                        selectedImageUri = null
                        selectedImageBase64 = null
                    }
                }) {
                    Icon(Icons.Default.Send, "发送")
                }
            }
        }
    }
}

/**
 * 图片 base64 解码 → Bitmap
 */
@Composable
fun rememberBase64Bitmap(base64: String): Bitmap? {
    return remember(base64) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }
}

@Composable
fun MessageBubble(msg: MessageEntity) {
    val isUser = msg.role == "user"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp).widthIn(max = 280.dp)) {
                // 图片
                msg.imageBase64?.let { b64 ->
                    val bitmap = rememberBase64Bitmap(b64)
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "用户图片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                // 文本
                if (msg.content.isNotBlank() && msg.content != "[图片]") {
                    Text(text = msg.content)
                } else if (msg.imageBase64 != null && msg.content == "[图片]") {
                    // 纯图片消息，不显示文字
                }
            }
        }
    }
}
```

---

## 6. 安全配置

### res/xml/network_security_config.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

AndroidManifest.xml: `android:networkSecurityConfig="@xml/network_security_config"`

---

## 7. MainActivity

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { ChatScreen() }
        }
    }
}
```

---

## 8. 依赖（build.gradle.kts）

```kotlin
// Compose BOM
implementation(platform("androidx.compose:compose-bom:2024.06.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.activity:activity-compose:1.9.0")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

// Room
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// OkHttp
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Coil (图片加载)
implementation("io.coil-kt:coil-compose:2.6.0")
```

---

## 9. MVP 行为清单

| 行为 | 状态 |
|------|:--:|
| 发送文本消息 | ✅ |
| SSE 流式接收 + 实时渲染 | ✅ |
| 从相册选图 + 发送 | ✅ |
| 图片在气泡中显示 | ✅ |
| 带图片 + 文字一起发 | ✅ |
| 纯图片消息 | ✅ |
| 旋转屏幕不丢消息 | ✅ (Room) |
| 旋转屏幕不丢输入框 | ✅ (rememberSaveable) |
| 旋转屏幕不丢已选图 | ✅ (rememberSaveable uri) |
| HTTP 错误提示 | ✅ |
| 停止生成 | ✅ |
| 自动滚动到底部 | ✅ |

### 故意不做

- 拍照（比相册多权限处理，Phase 2）
- 多对话管理
- Session 系统
- Markdown 渲染
- 设置页（API Key 硬编码）
- 错误重试
- Hilt DI
- 文件上传（Hermes API 不支持非图片 data URL）

---

## 10. 启动检查清单

1. API Key 填入 HermesClient 构造参数
2. `curl http://127.0.0.1:8642/health` → 正常
3. `network_security_config.xml` 已配置 + Manifest 引用
4. 发"你好" → 流式回复 ✅
5. 选图 + 发"这图里是什么" → 收到视觉分析回复 ✅
