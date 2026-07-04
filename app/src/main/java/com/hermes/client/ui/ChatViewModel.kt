package com.hermes.client.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import com.hermes.client.data.AppDatabase
import com.hermes.client.data.MessageDao
import com.hermes.client.data.MessageEntity
import com.hermes.client.data.HermesClient
import com.hermes.client.data.SseParser
import com.hermes.client.data.ToolProgress

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db: AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "hermes.db"
    ).build()
    private val dao: MessageDao = db.messageDao()

    private val prefs = application.getSharedPreferences("hermes", Context.MODE_PRIVATE)
    internal var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) { prefs.edit().putString("api_key", value).apply() }

    private val client: HermesClient
        get() = HermesClient("http://127.0.0.1:8642", apiKey)
    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    val messages: StateFlow<List<MessageEntity>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus

    private val _toolRunning = MutableStateFlow(false)
    val toolRunning: StateFlow<Boolean> = _toolRunning

    private var currentJob: Job? = null
    private var currentResponse: Response? = null

    fun sendMessage(
        text: String,
        imageBase64: String? = null,
        mimeType: String = "image/jpeg"
    ) {
        // Cancel any ongoing generation before starting new one
        stopGeneration()
        _isSending.value = true
        viewModelScope.launch {
            val displayText = if (imageBase64 != null && text.isBlank()) "[图片]" else text
            dao.insert(MessageEntity(role = "user", content = displayText, imageBase64 = imageBase64, timestamp = System.currentTimeMillis()))
            val assistantId = dao.insert(MessageEntity(role = "assistant", content = "", imageBase64 = null, timestamp = System.currentTimeMillis()))

            _isStreaming.value = true
            _streamingContent.value = ""
            _toolStatus.value = "💭 思考中..."
            _toolRunning.value = false

            currentJob = launch(Dispatchers.IO) {
                try {
                    val historyJson = buildHistoryJson(text, imageBase64, mimeType)
                    val response = client.chatStream(historyJson)
                    currentResponse = response

                    if (!response.isSuccessful) {
                        val errorMsg = "HTTP ${response.code}: ${response.body?.string()}"
                        dao.update(MessageEntity(id = assistantId, role = "assistant", content = "[错误] $errorMsg", timestamp = System.currentTimeMillis()))
                        return@launch
                    }

                    val body = response.body
                    if (body == null) {
                        dao.update(MessageEntity(id = assistantId, role = "assistant", content = "[错误] 空响应", timestamp = System.currentTimeMillis()))
                        return@launch
                    }

                    val contentBuffer = StringBuilder()
                    val scope = this

                    SseParser.parse(
                        responseBody = body,
                        onDelta = { delta ->
                            contentBuffer.append(delta)
                            _streamingContent.value = contentBuffer.toString()
                        },
                        onToolCall = { progress ->
                            if (progress.status == "running") {
                                _toolRunning.value = true
                                _toolStatus.value = progress.label ?: toolLabel(progress.tool)
                            } else if (progress.status == "completed") {
                                _toolRunning.value = false
                                _toolStatus.value = "✅ ${progress.label ?: toolLabel(progress.tool)}"
                            }
                        },
                        onDone = {
                            scope.launch {
                                dao.update(MessageEntity(id = assistantId, role = "assistant", content = contentBuffer.toString(), timestamp = System.currentTimeMillis()))
                            }
                        },
                        onError = { err ->
                            val finalMsg = if (currentJob?.isCancelled == true) {
                                contentBuffer.toString().ifEmpty { "[已停止]" }
                            } else {
                                contentBuffer.toString().ifEmpty { "[错误] $err" }
                            }
                            scope.launch {
                                dao.update(MessageEntity(id = assistantId, role = "assistant", content = finalMsg, timestamp = System.currentTimeMillis()))
                            }
                        }
                    )
                } catch (e: Exception) {
                    val errorText = if (currentJob?.isCancelled == true) "[已停止]" else "[错误] ${e.message}"
                    dao.update(MessageEntity(id = assistantId, role = "assistant", content = errorText, timestamp = System.currentTimeMillis()))
                } finally {
                    _streamingContent.value = ""
                    _isStreaming.value = false
                    _toolRunning.value = false
                    _toolStatus.value = null
                    _isSending.value = false
                }
            }
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        currentResponse?.close()
    }

    private suspend fun buildHistoryJson(
        currentText: String,
        currentImageBase64: String?,
        currentMimeType: String
    ): String {
        val history = dao.getAll().first()
            .filter { it.content.isNotBlank() }
            .takeLast(50)

        val array = JSONArray()
        for (msg in history) {
            val obj = JSONObject()
            obj.put("role", msg.role)
            if (msg.imageBase64 != null) {
                val contentArray = JSONArray()
                if (msg.content.isNotBlank() && msg.content != "[图片]") {
                    contentArray.put(JSONObject().apply { put("type", "text"); put("text", msg.content) })
                }
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,${msg.imageBase64}") })
                })
                obj.put("content", contentArray)
            } else {
                obj.put("content", msg.content)
            }
            array.put(obj)
        }

        val userObj = JSONObject()
        userObj.put("role", "user")
        if (currentImageBase64 != null) {
            val contentArray = JSONArray()
            if (currentText.isNotBlank()) {
                contentArray.put(JSONObject().apply { put("type", "text"); put("text", currentText) })
            }
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", "data:$currentMimeType;base64,$currentImageBase64") })
            })
            userObj.put("content", contentArray)
        } else {
            userObj.put("content", currentText)
        }
        array.put(userObj)
        return array.toString()
    }

    private fun toolLabel(name: String): String = when (name) {
        "web_search" -> "🔍 搜索中..."
        "web_extract" -> "📄 提取内容..."
        "terminal" -> "💻 执行命令..."
        "read_file" -> "📖 读取文件..."
        "write_file" -> "✏️ 写入文件..."
        "search_files" -> "🔎 搜索文件..."
        "delegate_task" -> "🤖 委派任务..."
        "vision_analyze" -> "👁️ 分析图片..."
        "memory" -> "🧠 读取记忆..."
        else -> "⚙️ $name"
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        currentResponse?.close()
        db.close()
    }
}
