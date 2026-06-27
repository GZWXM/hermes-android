package com.hermes.client.data

import java.time.Duration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HermesClient(private val baseUrl: String, private val apiKey: String) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(180))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .build())
        }
        .build()

    fun chatStream(messagesJson: String): okhttp3.Response {
        val requestBody = """
            {
                "model": "hermes-agent",
                "messages": $messagesJson,
                "stream": true
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        return client.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(requestBody)
                .build()
        ).execute()
    }
}
