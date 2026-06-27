package com.hermes.client.ui

import android.app.Application
import android.content.Context
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

    private var currentJob: Job? = null
    private var currentResponse: Response? = null

    fun sendMessage(
        text: String,
        imageBase64: String? = null,
        mimeType: String = "image/jpeg"
    ) {
        viewModelScope.launch {
            val displayText = if (imageBase64 != null && text.isBlank()) "[图片]" else text
            dao.insert(MessageEntity(role = "user", content = displayText, imageBase64 = imageBase64, timestamp = System.currentTimeMillis()))
            val assistantId = dao.insert(MessageEntity(role = "assistant", content = "", imageBase64 = null, timestamp = System.currentTimeMillis()))

            _isStreaming.value = true
            _streamingContent.value = ""

            currentJob = launch(Dispatchers.IO) {
                try {
                    val historyJson = buildHistoryJson(text, imageBase64, mimeType)
                    val response = client.chatStream(historyJson)
                    currentResponse = response

                    if (!response.isSuccessful) {
                        val errorMsg = "HTTP ${response.code}: ${response.body?.string()}"
                        dao.update(MessageEntity(id = assistantId, role = "assistant", content = "[错误] $errorMsg", timestamp = System.currentTimeMillis()))
                        _isStreaming.value = false
                        return@launch
                    }

                    val contentBuffer = StringBuilder()
                    var updateCount = 0

                    // 捕获 scope，供非 suspend 回调内调用 suspend dao.update
                    val scope = this

                    SseParser.parse(
                        responseBody = response.body!!,
                        onDelta = { delta ->
                            contentBuffer.append(delta)
                            updateCount++
                            _streamingContent.value = contentBuffer.toString()
                            if (updateCount % 10 == 0) {
                                scope.launch {
                                    dao.update(MessageEntity(id = assistantId, role = "assistant", content = contentBuffer.toString(), timestamp = System.currentTimeMillis()))
                                }
                            }
                        },
                        onDone = {
                            scope.launch {
                                dao.update(MessageEntity(id = assistantId, role = "assistant", content = contentBuffer.toString(), timestamp = System.currentTimeMillis()))
                            }
                            _streamingContent.value = ""
                            _isStreaming.value = false
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
                            _streamingContent.value = ""
                            _isStreaming.value = false
                        }
                    )
                } catch (e: Exception) {
                    val errorText = if (currentJob?.isCancelled == true) "[已停止]" else "[错误] ${e.message}"
                    dao.update(MessageEntity(id = assistantId, role = "assistant", content = errorText, timestamp = System.currentTimeMillis()))
                    _streamingContent.value = errorText
                    _isStreaming.value = false
                }
            }
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        currentResponse?.close()
        _isStreaming.value = false
        _streamingContent.value = ""
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

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        currentResponse?.close()
        db.close()
    }
}
