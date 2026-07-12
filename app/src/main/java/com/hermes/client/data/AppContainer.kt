package com.hermes.client.data

import android.content.Context
import androidx.room.Room

object AppContainer {
    @Volatile
    private var db: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        return db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "hermes.db"
            ).fallbackToDestructiveMigration().build().also { db = it }
        }
    }

    fun getMessageDao(context: Context): MessageDao {
        return getDatabase(context).messageDao()
    }

    fun getChatRepository(context: Context, baseUrl: String, apiKey: String): ChatRepository {
        return ChatRepository(
            messageDao = getMessageDao(context),
            hermesClient = HermesClient(baseUrl, apiKey)
        )
    }
}
