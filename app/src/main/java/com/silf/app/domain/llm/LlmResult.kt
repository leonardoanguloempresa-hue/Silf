package com.silf.app.domain.llm

sealed interface LlmResult {
    data class Token(val text: String) : LlmResult
    data object Completed : LlmResult
    data class Failed(val error: Throwable) : LlmResult
}
