package com.hermes.client.data

import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException

data class ToolProgress(
    val tool: String,
    val status: String,  // "running" | "completed"
    val label: String?,
)

object SseParser {

    fun parse(
        responseBody: ResponseBody,
        onDelta: (String) -> Unit,
        onToolCall: (ToolProgress) -> Unit = {},
        onReasoning: (String) -> Unit = {},
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
                val trimmed = line.trimStart()
                if (trimmed.startsWith("event:")) {
                    currentEvent = trimmed.removePrefix("event:").trim()
                    dataLines.clear() // new event, discard orphan data
                } else if (trimmed.startsWith("data:")) {
                    // SSE spec allows 0+ spaces after "data:"
                    dataLines.add(trimmed.removePrefix("data:").trimStart())
                } else if (line.isEmpty()) {
                    // end of event block
                    if (dataLines.isNotEmpty()) {
                        val eventData = dataLines.joinToString("\n")
                        dataLines.clear()

                        if (eventData.trim() == "[DONE]") {
                            onDone()
                            return
                        }

                        // Tool progress events
                        if (currentEvent == "hermes.tool.progress") {
                            try {
                                val json = JSONObject(eventData)
                                val tool = json.optString("tool", "")
                                val status = json.optString("status", "running")
                                val label = json.optString("label", null)
                                if (tool.isNotBlank()) {
                                    onToolCall(ToolProgress(tool = tool, status = status, label = label))
                                }
                            } catch (_: Exception) {}
                            currentEvent = null
                            continue
                        }

                        val content = extractContent(eventData)
                        if (content != null) {
                            onDelta(content)
                        }

                        // Also extract reasoning_content if present
                        val reasoning = extractReasoning(eventData)
                        if (reasoning != null) {
                            onReasoning(reasoning)
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

    private fun extractReasoning(jsonStr: String): String? {
        return try {
            val json = JSONObject(jsonStr)
            val choices = json.optJSONArray("choices") ?: return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            val delta = firstChoice.optJSONObject("delta") ?: return null
            delta.optString("reasoning_content", null)
        } catch (e: Exception) {
            null
        }
    }
}
