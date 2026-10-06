package com.silf.app.domain.llm

import android.content.Context
import android.util.Log
import com.silf.app.llm.LlamaNative
import com.silf.app.llm.TokenCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor LLM local basado en llama.cpp.
 * Implementado como Singleton para que el modelo persista en RAM
 * sobreviviendo el ciclo de vida de MainActivity y esté disponible para AssistantActivity.
 */
class LlamaCppEngine private constructor() : LlmEngine {
    private val native = LlamaNative()
    private val loadMutex = Mutex()
    private var modelHandle: Long = 0L
    private var systemContext: String = "Eres Silf, un asistente útil y preciso."
    private var appContext: Context? = null

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _loadedModelPath = MutableStateFlow<String?>(null)
    val loadedModelPath: StateFlow<String?> = _loadedModelPath.asStateFlow()
    val currentModelPath: String? get() = _loadedModelPath.value

    fun setApplicationContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * Intenta auto-cargar el modelo si hay una ruta GGUF guardada en preferencias
     * y el archivo aún existe en disco.
     */
    fun initAutoLoad(context: Context) {
        setApplicationContext(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_SAVED_GGUF_PATH, null)
        if (!savedPath.isNullOrBlank() && !isReady()) {
            val file = File(savedPath)
            if (file.exists() && file.length() > 0) {
                Log.i(TAG, "Ruta GGUF guardada detectada: $savedPath. Iniciando auto-carga en RAM...")
                CoroutineScope(Dispatchers.IO).launch {
                    loadModel(savedPath)
                }
            } else {
                Log.w(TAG, "Ruta GGUF guardada '$savedPath' no existe en disco o está vacía")
            }
        }
    }

    override suspend fun loadModel(filePath: String): Boolean = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            runCatching {
                if (modelHandle != 0L) {
                    Log.i(TAG, "Descargando modelo previo en handle: $modelHandle")
                    native.unload(modelHandle)
                    modelHandle = 0L
                    _loadedModelPath.value = null
                }

                val file = File(filePath)
                if (!file.exists()) {
                    throw IllegalArgumentException("Archivo de modelo no encontrado: $filePath")
                }

                // contextSize 2048 y threads 4 iniciales.
                val handle = native.loadModel(filePath, true, 4, 2048)
                if (handle == 0L) {
                    throw OutOfMemoryError("Error: Memoria insuficiente para cargar el modelo")
                }

                modelHandle = handle
                _loadedModelPath.value = filePath
                _lastError.value = null

                // Guardar la ruta GGUF para que el modelo sobreviva reinicios
                saveModelPath(filePath)
                Log.i(TAG, "Modelo GGUF cargado exitosamente en RAM: $filePath (handle=$handle)")
                true
            }.getOrElse { e ->
                modelHandle = 0L
                _loadedModelPath.value = null
                val errorMsg = e.message ?: "Error: Memoria insuficiente para cargar el modelo"
                _lastError.value = errorMsg
                Log.e(TAG, "Error cargando modelo GGUF: $errorMsg", e)
                false
            }
        }
    }

    private fun saveModelPath(path: String) {
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_SAVED_GGUF_PATH, path).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error al guardar ruta GGUF en SharedPreferences", e)
            }
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

        // Si el caller ya proveyó el prompt envuelto en ChatML estricto (iniciando con <|im_start|>system),
        // se usa DIRECTAMENTE sin alterarlo ni agregarle nada.
        val finalPrompt = if (prompt.trimStart().startsWith("<|im_start|>system")) {
            prompt
        } else if (prompt.trimStart().startsWith("<|im_start|>")) {
            val effectiveSystem = systemPrompt?.takeIf { it.isNotBlank() } ?: systemContext
            "<|im_start|>system\n$effectiveSystem<|im_end|>\n$prompt"
        } else {
            val effectiveSystem = systemPrompt?.takeIf { it.isNotBlank() } ?: systemContext
            val userMsg = prompt.trim()
            "<|im_start|>system\n$effectiveSystem<|im_end|>\n" +
            "<|im_start|>user\n${userMsg}<|im_end|>\n" +
            "<|im_start|>assistant\n"
        }

        val stopTokens = listOf("<|im_end|>", "<|endoftext|>")
        // AtomicBoolean garantiza visibilidad entre el hilo de coroutine y el hilo nativo C++
        val isStopped = AtomicBoolean(false)

        native.generate(modelHandle, finalPrompt, 512, 0.1f, object : TokenCallback {
            override fun onToken(text: String) {
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
    }.buffer(Channel.UNLIMITED)

    fun updateSystemContext(context: String) { systemContext = context }

    override fun stopGeneration() {
        if (modelHandle != 0L) native.cancel(modelHandle)
    }

    override fun isReady(): Boolean = modelHandle != 0L

    override fun unload() {
        if (modelHandle != 0L) {
            native.unload(modelHandle)
            modelHandle = 0L
            _loadedModelPath.value = null
        }
    }

    companion object {
        private const val TAG = "LlamaCppEngine"
        const val PREFS_NAME = "silf_client_prefs"
        const val KEY_SAVED_GGUF_PATH = "last_loaded_gguf_path"

        @Volatile
        private var instance: LlamaCppEngine? = null

        fun getInstance(context: Context? = null): LlamaCppEngine {
            return instance ?: synchronized(this) {
                instance ?: LlamaCppEngine().also { engine ->
                    instance = engine
                    if (context != null) {
                        engine.initAutoLoad(context.applicationContext)
                    }
                }
            }
        }

        fun init(context: Context): LlamaCppEngine {
            return getInstance(context)
        }

        /** Permite llamar LlamaCppEngine() devolviendo la instancia Singleton */
        operator fun invoke(): LlamaCppEngine = getInstance()

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
