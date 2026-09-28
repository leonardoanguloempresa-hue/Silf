package com.silf.app.di

import android.content.Context
import com.silf.app.domain.llm.FakeLlmEngine
import com.silf.app.domain.llm.LlmEngine

class AppContainer(@Suppress("unused") context: Context) {
    val llmEngine: LlmEngine = FakeLlmEngine()
}
