package com.hermes.client.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val hermesClient: HermesClient
) {
    // ── Conversations ──

    val allConversations: Flow<List<ConversationEntity>> = conversationDao.getAll()

    suspend fun getConversationsOnce(): List<ConversationEntity> {
        return conversationDao.getAllOnce()
    }

    suspend fun createConversation(title: String = "新对话"): ConversationEntity {
        val conv = ConversationEntity(
            uuid = UUID.randomUUID().toString(),
            title = title
        )
        conversationDao.insert(conv)
        return conv
    }

    suspend fun getConversation(uuid: String): ConversationEntity? {
        return conversationDao.getByUuid(uuid)
    }

    suspend fun updateConversation(conv: ConversationEntity) {
        conversationDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteConversation(uuid: String) {
        messageDao.clearConversation(uuid)
        conversationDao.deleteByUuid(uuid)
    }

    // ── Messages ──

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.getByConversation(conversationId)
    }

    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity> {
        return messageDao.getByConversationOnce(conversationId)
    }

    suspend fun saveMessage(message: MessageEntity): Long = messageDao.insert(message)

    suspend fun deleteMessage(message: MessageEntity) = messageDao.delete(message)

    suspend fun clearConversation(conversationId: String) = messageDao.clearConversation(conversationId)

    // ── Streaming ──

    fun streamChat(messagesJson: String): okhttp3.Response {
        return hermesClient.chatStream(messagesJson)
    }
}
