package com.silf.app.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.silf.app.SilfApplication
import com.silf.app.data.preferences.PreferencesManager
import com.silf.app.data.repository.ChatRepository
import com.silf.app.domain.llm.LlamaCppEngine
import com.silf.app.domain.llm.LlmEngine

class ChatViewModelFactory(
    private val llmEngine: LlmEngine? = null,
    private val chatRepository: ChatRepository,
    private val preferencesManager: PreferencesManager? = null,
    private val context: Context? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val engine = llmEngine ?: LlamaCppEngine.getInstance()
        val ctx = context ?: SilfApplication.instance
        return ChatViewModel(
            llmEngine = engine,
            chatRepository = chatRepository,
            preferencesManager = preferencesManager,
            context = ctx
        ) as T
    }
}

