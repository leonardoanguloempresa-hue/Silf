package com.silf.app.domain.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

class FakeLlmEngine : LlmEngine {
    private val closed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun generateResponse(prompt: String): Flow<LlmResult> = flow {
        check(!closed.get()) { "El motor está cerrado" }
        cancelled.set(false)
        val response = "Hola, soy Silf. Motor simulado activo. Recibí: \"$prompt\"."
        response.split(" ").forEach { word ->
            if (cancelled.get()) return@flow
            emit(LlmResult.Token("$word "))
            delay(80L)
        }
        if (!cancelled.get()) emit(LlmResult.Completed)
    }
    override fun cancel() { cancelled.set(true) }
    override fun close() {
        closed.set(true)
        cancelled.set(true)
    }
    override fun isReady(): Boolean = !closed.get()
}
