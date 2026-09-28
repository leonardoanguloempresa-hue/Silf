package com.silf.app.llm

data class NativeModelConfig(
    val useMmap: Boolean = true,
    val threads: Int = 4,
    val contextSize: Int = 2048, // Empezar seguro para evitar OutOfMemory
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f
)
