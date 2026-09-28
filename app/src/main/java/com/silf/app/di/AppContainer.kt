package com.silf.app.di

import android.content.Context
import com.silf.app.domain.download.DownloadRepository
import com.silf.app.domain.llm.FakeLlmEngine
import com.silf.app.domain.llm.LlmEngine

class AppContainer(context: Context) {
    val llmEngine: LlmEngine = FakeLlmEngine()
    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(context.applicationContext)
    }
}
