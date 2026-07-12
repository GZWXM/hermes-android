package com.hermes.client.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import com.hermes.client.data.AppContainer
import com.hermes.client.data.ChatError
import com.hermes.client.data.ChatRepository
import com.hermes.client.data.MessageEntity
import com.hermes.client.data.SseParser

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("hermes", Context.MODE_PRIVATE)
    internal var apiKey: String
        get() {
            val raw = prefs.getString("api_key", "") ?: ""
            return raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        }
        set(value) {
            val clean = value.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
            prefs.edit().putString("api_key", clean).apply()
        }

    private val repository: ChatRepository by lazy {
        AppContainer.getChatRepository(application, "http://127.0.0.1:8642", apiKey)
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    val messages: StateFlow<List<MessageEntity>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent

    private val _thinkingContent = MutableStateFlow("")
    val thinkingContent: StateFlow<String> = _thinkingContent

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _toolStatus = MutableStateFlow<String?>(null)
    val toolStatus: StateFlow<String?> = _toolStatus

    private val _toolRunning = MutableStateFlow(false)
    val toolRunning: StateFlow<Boolean> = _toolRunning

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error

    private var currentJob: Job? = null
    private var currentResponse: Response? = null

    fun sendMessage(
        text: String,
        imageBase64: String? = null,
        mimeType: String = "image/jpeg"
    ) {
        stopGeneration()

        // --- Slash command handling ---
        if (text.startsWith("/") && imageBase64 == null) {
            handleSlashCommand(text)
            return
        }

        if (!hasApiKey()) {
            _error.value = ChatError.Unauthorized
            return
        }

        _isSending.value = true
        viewModelScope.launch {
            try {
                val displayText = if (imageBase64 != null && text.isBlank()) "[图片]" else text
                repository.saveMessage(MessageEntity(role = "user", content = displayText, imageBase64 = imageBase64, timestamp = System.currentTimeMillis()))

                _isStreaming.value = true
                _streamingContent.value = ""
                _thinkingContent.value = ""
                _toolStatus.value = "💭 思考中..."
                _toolRunning.value = false

                currentJob = launch(Dispatchers.IO) {
                    try {
                        val historyJson = buildHistoryJson(text, imageBase64, mimeType)
                        val response = repository.streamChat(historyJson)
                        currentResponse = response

                        if (!response.isSuccessful) {
                            val errorMsg = response.body?.string() ?: "未知错误"
                            when (response.code) {
                                401 -> _error.value = ChatError.Unauthorized
                                in 500..599 -> _error.value = ChatError.Server(response.code, errorMsg)
                                else -> _error.value = ChatError.Network("HTTP ${response.code}", errorMsg)
                            }
                            return@launch
                        }

                        val body = response.body
                        if (body == null) {
                            _error.value = ChatError.Network("服务器返回了空响应")
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
                            onReasoning = { text ->
                                _thinkingContent.value = text
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
                                    val finalContent = contentBuffer.toString()
                                    if (finalContent.isNotBlank()) {
                                        repository.saveMessage(MessageEntity(role = "assistant", content = finalContent, timestamp = System.currentTimeMillis()))
                                    }
                                    _streamingContent.value = ""
                                }
                            },
                            onError = { err ->
                                val isCancelled = currentJob?.isCancelled == true
                                val finalMsg = contentBuffer.toString().ifEmpty {
                                    if (isCancelled) "[已停止]" else "[错误] $err"
                                }
                                _streamingContent.value = finalMsg
                                if (isCancelled) {
                                    _error.value = ChatError.Cancelled
                                } else {
                                    _error.value = ChatError.Network(err)
                                }
                                scope.launch {
                                    if (finalMsg.isNotBlank() && !finalMsg.startsWith("[")) {
                                        repository.saveMessage(MessageEntity(role = "assistant", content = finalMsg, timestamp = System.currentTimeMillis()))
                                    }
                                }
                            }
                        )
                    } catch (e: Exception) {
                        if (currentJob?.isCancelled != true) {
                            val errorText = "[错误] ${e.message}"
                            _streamingContent.value = errorText
                            _error.value = ChatError.Network(e.message ?: "未知错误")
                        }
                    } finally {
                        _thinkingContent.value = ""
                        _isStreaming.value = false
                        _toolRunning.value = false
                        _toolStatus.value = null
                        _isSending.value = false
                    }
                }
            } catch (e: Exception) {
                _streamingContent.value = ""
                _isStreaming.value = false
                _toolRunning.value = false
                _toolStatus.value = null
                _isSending.value = false
                _error.value = ChatError.Unknown(e.message ?: "未知错误")
                e.printStackTrace()
            }
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        currentResponse?.close()
    }

    private fun handleSlashCommand(command: String) {
        when {
            command == "/reset" || command == "/new" -> {
                viewModelScope.launch {
                    repository.clearAllMessages()
                    _streamingContent.value = ""
                    _thinkingContent.value = ""
                    _toolStatus.value = "🔄 会话已重置"
                    kotlinx.coroutines.delay(1500)
                    _toolStatus.value = null
                }
            }
            command.startsWith("/") -> {
                _error.value = ChatError.Network(
                    "未知命令: $command\n\n支持的命令:\n/reset, /new  — 清空当前对话"
                )
            }
        }
    }

    fun clearMessages() {
        viewModelScope.launch { repository.clearAllMessages() }
    }

    fun deleteMessage(msg: MessageEntity) {
        viewModelScope.launch { repository.deleteMessage(msg) }
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun buildHistoryJson(
        currentText: String,
        currentImageBase64: String?,
        currentMimeType: String
    ): String {
        val messages = repository.allMessages
        val array = JSONArray()
        for (msg in messages) {
            if (msg.content.isBlank() && msg.imageBase64 == null) continue
            if (msg.content.startsWith("[错误]") || msg.content.startsWith("[已停止]")) continue
            if (msg.content.startsWith("[")) continue
            val obj = JSONObject()
            obj.put("role", msg.role)
            if (msg.imageBase64 != null) {
                val content = JSONArray()
                content.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:$mimeType;base64,${msg.imageBase64}")
                    })
                })
                if (msg.content.isNotBlank()) {
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                obj.put("content", content)
            } else {
                obj.put("content", msg.content)
            }
            array.put(obj)
        }
        // Append current user message
        val cur = JSONObject()
        cur.put("role", "user")
        if (currentImageBase64 != null) {
            val content = JSONArray()
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:$currentMimeType;base64,$currentImageBase64")
                })
            })
            if (currentText.isNotBlank()) {
                content.put(JSONObject().apply {
                    put("type", "text")
                    put("text", currentText)
                })
            }
            cur.put("content", content)
        } else {
            cur.put("content", currentText)
        }
        array.put(cur)
        return array.toString()
    }

    private fun toolLabel(name: String): String = when (name) {
        "web_search" -> "🔍 搜索中..."
        "web_extract" -> "📄 提取内容..."
        "terminal" -> "💻 执行命令..."
        "web_extract_v2" -> "📄 提取内容..."
        "vision_analyze" -> "👁️ 分析图片..."
        "read_file" -> "📖 读取文件..."
        else -> "⚙️ $name"
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        currentResponse?.close()
    }
}
