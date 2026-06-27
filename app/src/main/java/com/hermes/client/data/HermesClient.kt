package com.hermes.client.data

import okhttp3.*
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class HermesClient(private val baseUrl: String, private val apiKey: String) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .build()
            chain.proceed(request)
        }
        .build()

    fun chatStream(messagesJson: String): Response {
        val jsonArray = JSONArray(messagesJson)
        val requestBody = RequestBody.create(
            MediaType.parse("application/json"),
            """
            {
                "model": "hermes-agent",
                "messages": $jsonArray,
                "stream": true
            }
            """.trimIndent()
        )
        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(requestBody)
            .build()
        return client.newCall(request).execute()
    }
}
