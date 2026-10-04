package com.silf.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.silf.app.data.preferences.PreferencesManager
import com.silf.app.data.repository.ChatRepository
import com.silf.app.domain.llm.LlmEngine

class ChatViewModelFactory(
    private val llmEngine: LlmEngine,
    private val chatRepository: ChatRepository,
    private val preferencesManager: PreferencesManager? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(llmEngine, chatRepository, preferencesManager = preferencesManager) as T
    }
}

