package com.silf.app.di

import android.content.Context
import com.silf.app.data.db.SilfDatabase
import com.silf.app.data.preferences.PreferencesManager
import com.silf.app.data.repository.ChatRepository
import com.silf.app.domain.download.DownloadRepository
import com.silf.app.domain.llm.LlamaCppEngine
import com.silf.app.domain.llm.LlmEngine

class AppContainer(context: Context) {
    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(context.applicationContext)
    }

    val llmEngine: LlmEngine by lazy {
        LlamaCppEngine()
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(context.applicationContext)
    }

    val database: SilfDatabase by lazy {
        SilfDatabase.getInstance(context.applicationContext)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }
}

