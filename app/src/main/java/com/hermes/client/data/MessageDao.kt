package com.hermes.client.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
