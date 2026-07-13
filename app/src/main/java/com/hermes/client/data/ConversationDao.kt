package com.hermes.client.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
