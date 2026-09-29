package com.silf.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert
    suspend fun insertMessage(message: ChatEntity)

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC, id ASC")
    fun getAllMessages(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getLastMessages(limit: Int): List<ChatEntity>

    @Query("DELETE FROM chat_messages")
    suspend fun clearHistory()
}
