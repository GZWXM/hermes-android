package com.hermes.client.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val conversationId: String = "",
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val fileBase64: String? = null,
    val fileName: String? = null,
    val thinkingContent: String? = null,
    val timestamp: Long
)
