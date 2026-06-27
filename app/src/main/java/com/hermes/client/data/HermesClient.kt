package com.hermes.client.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HermesClient(private val baseUrl: String, private val apiKey: String) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
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
