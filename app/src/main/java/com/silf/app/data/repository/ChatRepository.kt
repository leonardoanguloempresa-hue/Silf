package com.silf.app.data.repository

import com.silf.app.data.db.ChatDao
import com.silf.app.data.db.ChatEntity
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    fun getAllMessages(): Flow<List<ChatEntity>> = chatDao.getAllMessages()

    suspend fun insertMessage(text: String, isUser: Boolean) {
        chatDao.insertMessage(ChatEntity(text = text, isUser = isUser))
    }

    suspend fun clearHistory() {
        chatDao.clearHistory()
    }
}
