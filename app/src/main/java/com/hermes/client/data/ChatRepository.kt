package com.hermes.client.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(
    private val messageDao: MessageDao,
    private val hermesClient: HermesClient
) {
    val allMessages: Flow<List<MessageEntity>> = messageDao.getAll()

    suspend fun saveMessage(message: MessageEntity): Long = messageDao.insert(message)

    suspend fun deleteMessage(message: MessageEntity) = messageDao.delete(message)

    suspend fun clearAllMessages() = messageDao.clearAll()

    fun streamChat(messagesJson: String): okhttp3.Response {
        return hermesClient.chatStream(messagesJson)
    }
}
