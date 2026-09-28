package com.silf.app.domain.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LlmEngine {
    val lastError: StateFlow<String?>
    suspend fun loadModel(filePath: String): Boolean
    fun generateResponseStream(prompt: String): Flow<String>
    fun stopGeneration()
    fun isReady(): Boolean
    fun unload()
}
