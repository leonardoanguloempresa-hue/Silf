package com.silf.app.domain.llm

import com.silf.app.llm.LlamaNative
import com.silf.app.llm.TokenCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class LlamaCppEngine : LlmEngine {
    private val native = LlamaNative()
    private var modelHandle: Long = 0L
    private var systemContext: String = "Eres Silf, un asistente útil y preciso."

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override suspend fun loadModel(filePath: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (modelHandle != 0L) {
                native.unload(modelHandle)
                modelHandle = 0L
            }
            // contextSize 2048 y threads 4 iniciales.
            val handle = native.loadModel(filePath, true, 4, 2048)
            if (handle == 0L) {
                throw OutOfMemoryError("Error: Memoria insuficiente para cargar el modelo")
            }
            modelHandle = handle
            _lastError.value = null
            true
        }.getOrElse { _ ->
            modelHandle = 0L
            _lastError.value = "Error: Memoria insuficiente para cargar el modelo"
            false
        }
    }

    /**
     * Genera una respuesta en streaming.
     * @param prompt Prompt YA FORMATEADO en ChatML por el caller (ViewModel),
     *               o texto sin procesar que se formateará con ChatML.
     */
    override fun generateResponseStream(prompt: String): Flow<String> =
        generateResponseStream(prompt, null)

    override fun generateResponseStream(prompt: String, systemPrompt: String?): Flow<String> = callbackFlow {
        if (modelHandle == 0L) {
            trySend("Error: Modelo no cargado.")
            close()
            return@callbackFlow
        }

        val effectiveSystem = systemPrompt?.takeIf { it.isNotBlank() } ?: systemContext

        val finalPrompt = if (prompt.trimStart().startsWith("<|im_start|>system")) {
            prompt
        } else if (prompt.trimStart().startsWith("<|im_start|>")) {
            "<|im_start|>system\n$effectiveSystem<|im_end|>\n$prompt"
        } else {
            val userMsg = prompt.trim()
            "<|im_start|>system\n$effectiveSystem<|im_end|>\n" +
            "<|im_start|>user\n${userMsg}<|im_end|>\n" +
            "<|im_start|>assistant\n"
        }

        val stopTokens = listOf("<|im_end|>", "<|endoftext|>")
        // AtomicBoolean garantiza visibilidad entre el hilo de coroutine y el hilo nativo C++
        // que llama a onToken() desde AttachCurrentThread.
        val isStopped = AtomicBoolean(false)

        native.generate(modelHandle, finalPrompt, 512, 0.4f, object : TokenCallback {
            override fun onToken(text: String) {
                // Doble guardia: AtomicBoolean + canal ya cerrado
                if (isStopped.get() || channel.isClosedForSend) return
                if (stopTokens.any { text.contains(it) }) {
                    if (isStopped.compareAndSet(false, true)) {
                        native.cancel(modelHandle)
                        close()
                    }
                    return
                }
                // Limpiar cualquier fragmento residual de stop token que llegue partido
                val clean = stopTokens.fold(text) { acc, stop -> acc.replace(stop, "") }
                if (clean.isNotEmpty()) {
                    trySend(clean)
                }
            }
            override fun onComplete() {
                if (!channel.isClosedForSend) close()
            }
            override fun onError(message: String) {
                if (!isStopped.get() && !channel.isClosedForSend) {
                    trySend("\n[Error: $message]")
                }
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

    companion object {
        /**
         * Reconstruye el prompt ChatML con historial de conversación.
         */
        fun formatChatPrompt(
            history: List<Pair<String, Boolean>>,
            currentUserMessage: String,
            systemMessage: String = "Eres Silf, un asistente útil y preciso."
        ): String {
            return buildString {
                append("<|im_start|>system\n$systemMessage<|im_end|>\n")
                for ((text, isUser) in history) {
                    val role = if (isUser) "user" else "assistant"
                    append("<|im_start|>$role\n${text.trim()}<|im_end|>\n")
                }
                append("<|im_start|>user\n${currentUserMessage.trim()}<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }
        }
    }
}
