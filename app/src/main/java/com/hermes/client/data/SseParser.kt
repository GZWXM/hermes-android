package com.hermes.client.data

import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException

object SseParser {

    fun parse(
        responseBody: ResponseBody,
        onDelta: (String) -> Unit,
        onToolCall: (String) -> Unit = {},
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val dataLines = mutableListOf<String>()
        var currentEvent: String? = null
        val source = responseBody.source()

        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith(":")) {
                    // keepalive comment
                    continue
                }
                if (line.startsWith("event: ")) {
                    currentEvent = line.removePrefix("event: ")
                } else if (line.startsWith("data: ")) {
                    dataLines.add(line.removePrefix("data: "))
                } else if (line.isEmpty()) {
                    // end of event block
                    if (dataLines.isNotEmpty()) {
                        val eventData = dataLines.joinToString("\n")
                        dataLines.clear()

                        if (eventData == "[DONE]") {
                            onDone()
                            return
                        }

                        // Tool progress events
                        if (currentEvent == "hermes.tool.progress") {
                            try {
                                val json = JSONObject(eventData)
                                val name = json.optString("name", "")
                                if (name.isNotBlank()) {
                                    onToolCall(name)
                                }
                            } catch (_: Exception) {}
                            currentEvent = null
                            continue
                        }

                        val content = extractContent(eventData)
                        if (content != null) {
                            onDelta(content)
                        }
                    }
                    currentEvent = null
                }
            }
        } catch (e: IOException) {
            onError(e.message ?: "IOException")
        } finally {
            try {
                source.close()
            } catch (_: IOException) {
                // ignore
            }
            try {
                responseBody.close()
            } catch (_: IOException) {
                // ignore
            }
        }
    }

    private fun extractContent(jsonStr: String): String? {
        return try {
            val json = JSONObject(jsonStr)
            val choices = json.optJSONArray("choices") ?: return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            if (firstChoice.has("finish_reason") && !firstChoice.isNull("finish_reason")) {
                return null
            }
            val delta = firstChoice.optJSONObject("delta") ?: return null
            delta.optString("content", null)
        } catch (e: Exception) {
            null
        }
    }
}
