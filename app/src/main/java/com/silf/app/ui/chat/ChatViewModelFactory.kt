package com.silf.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.silf.app.domain.llm.LlmEngine

class ChatViewModelFactory(private val llmEngine: LlmEngine) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(llmEngine) as T
    }
}
