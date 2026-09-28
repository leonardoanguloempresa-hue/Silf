package com.silf.app.domain.llm

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MediaPipeLlmEngine(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val modelPath: String
) : LlmEngine {
    override fun generateResponse(prompt: String): Flow<LlmResult> = flow {
        emit(LlmResult.Failed(UnsupportedOperationException("Implementación en Fase 3.")))
    }
    override fun cancel() {}
    override fun close() {}
    override fun isReady(): Boolean = false
}
