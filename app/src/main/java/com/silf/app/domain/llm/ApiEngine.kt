package com.silf.app.domain.llm

import android.util.Log
import com.silf.app.data.preferences.PreferencesManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Motor de red para la arquitectura Cliente-Servidor de Silf.
 * Se comunica por Wi-Fi con el servidor Ollama o compatible que corre en la PC del usuario.
 */
class ApiEngine(
    private val preferencesManager: PreferencesManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : LlmEngine {

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var activeCall: Call? = null

    override suspend fun loadModel(filePath: String): Boolean {
        // En modo cliente no se cargan modelos GGUF locales
        _lastError.value = null
        return true
    }

    override fun generateResponseStream(prompt: String): Flow<String> =
        generateResponseStream(prompt, systemPrompt = null)

    override fun generateResponseStream(prompt: String, systemPrompt: String?): Flow<String> = callbackFlow {
        val endpoint = preferencesManager.endpointUrl.value.ifBlank {
            PreferencesManager.DEFAULT_ENDPOINT_URL
        }
        val model = preferencesManager.modelName.value.ifBlank {
            PreferencesManager.DEFAULT_MODEL_NAME
        }

        // Construir JSON con formato Ollama (/api/generate)
        val json = JSONObject().apply {
            put("model", model)
            put("stream", true)

            if (!systemPrompt.isNullOrBlank()) {
                put("system", systemPrompt)
                put("prompt", prompt)
            } else if (prompt.contains("<|im_start|>system")) {
                // Si el prompt viene con ChatML compuesto, separar el bloque system del turno del usuario
                val sysPart = prompt.substringAfter("<|im_start|>system\n")
                    .substringBefore("<|im_end|>")
                    .trim()
                val remaining = prompt.substringAfter("<|im_end|>\n").trim()
                if (sysPart.isNotEmpty()) {
                    put("system", sysPart)
                }
                put("prompt", remaining)
            } else {
                put("prompt", prompt)
            }
        }

        Log.d(TAG, "Enviando POST a $endpoint con modelo: $model")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    val msg = "Error de conexión con $endpoint: ${e.localizedMessage ?: e.message}"
                    Log.e(TAG, msg, e)
                    _lastError.value = msg
                    trySend("⚠️ No se pudo conectar a la PC ($endpoint).\nVerifica que la PC esté encendida, en la misma red Wi-Fi y con Ollama activo.")
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errMsg = "Error del servidor HTTP ${resp.code}: ${resp.message}"
                        Log.e(TAG, errMsg)
                        _lastError.value = errMsg
                        trySend("⚠️ $errMsg")
                        close()
                        return
                    }

                    val source = resp.body?.source()
                    if (source == null) {
                        close()
                        return
                    }

                    try {
                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            try {
                                val chunk = JSONObject(line)
                                if (chunk.has("response")) {
                                    val token = chunk.getString("response")
                                    if (token.isNotEmpty()) {
                                        trySend(token)
                                    }
                                } else if (chunk.has("message")) {
                                    // Soporte para endpoints tipo /api/chat
                                    val messageObj = chunk.optJSONObject("message")
                                    val content = messageObj?.optString("content").orEmpty()
                                    if (content.isNotEmpty()) {
                                        trySend(content)
                                    }
                                }
                                if (chunk.optBoolean("done", false)) {
                                    break
                                }
                            } catch (_: Exception) {
                                // Texto plano
                                trySend(line)
                            }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            Log.e(TAG, "Error leyendo stream de respuesta", e)
                            trySend("\n[Error de transmisión: ${e.message}]")
                        }
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose {
            call.cancel()
            if (activeCall === call) {
                activeCall = null
            }
        }
    }

    override fun stopGeneration() {
        activeCall?.cancel()
        activeCall = null
    }

    override fun isReady(): Boolean {
        // En modo cliente estamos listos siempre que haya una URL configurada
        return preferencesManager.endpointUrl.value.isNotBlank()
    }

    override fun unload() {
        stopGeneration()
    }

    companion object {
        private const val TAG = "ApiEngine"
    }
}
