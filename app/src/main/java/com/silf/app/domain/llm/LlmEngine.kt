package com.silf.app.domain.llm

import kotlinx.coroutines.flow.Flow

interface LlmEngine {
    suspend fun loadModel(filePath: String): Boolean
    fun generateResponseStream(prompt: String): Flow<String>
    fun stopGeneration()
    fun isReady(): Boolean
    fun unload()
}
