package com.silf.app.domain.llm

import kotlinx.coroutines.flow.Flow

interface LlmEngine {
    fun generateResponse(prompt: String): Flow<LlmResult>
    fun cancel()
    fun close()
    fun isReady(): Boolean
}
