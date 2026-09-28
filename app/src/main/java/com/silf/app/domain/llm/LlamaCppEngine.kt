package com.silf.app.domain.llm

import com.silf.app.llm.LlamaNative
import com.silf.app.llm.TokenCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlamaCppEngine : LlmEngine {
    private val native = LlamaNative()
    private var modelHandle: Long = 0L
    private var systemContext: String = "Eres Silf, un asistente útil y preciso."

    override suspend fun loadModel(filePath: String): Boolean = withContext(Dispatchers.IO) {
        if (modelHandle != 0L) native.unload(modelHandle)
        // contextSize 2048 y threads 4 iniciales.
        modelHandle = native.loadModel(filePath, true, 4, 2048)
        modelHandle != 0L
    }

    override fun generateResponseStream(prompt: String): Flow<String> = callbackFlow {
        if (modelHandle == 0L) {
            trySend("Error: Modelo no cargado.")
            close()
            return@callbackFlow
        }

        val formatted = "<|im_start|>system\n$systemContext<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"

        native.generate(modelHandle, formatted, 512, 0.7f, object : TokenCallback {
            override fun onToken(text: String) { trySend(text) }
            override fun onComplete() { close() }
            override fun onError(msg: String) {
                trySend("\n[Error: $msg]")
                close()
            }
        })

        awaitClose { native.cancel(modelHandle) }
    }

    fun updateSystemContext(context: String) { systemContext = context }

    override fun stopGeneration() {
        if (modelHandle != 0L) native.cancel(modelHandle)
    }

    override fun isReady(): Boolean = modelHandle != 0L

    override fun unload() {
        if (modelHandle != 0L) {
            native.unload(modelHandle)
            modelHandle = 0L
        }
    }
}
