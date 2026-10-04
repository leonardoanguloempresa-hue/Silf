package com.silf.app.domain.llm

import android.util.Log
import com.silf.app.accessibility.SilfAccessibilityService
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
 * Se comunica por Wi-Fi con el servidor Ollama en la PC del usuario.
 */
class ApiEngine(
    private val preferencesManager: PreferencesManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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

        // Pre-concatenar el screenSnapshot del servicio de accesibilidad como System Prompt temporal
        val screenSnapshot = SilfAccessibilityService.screenSnapshot.value.trim()
        val effectiveSystemPrompt = buildString {
            if (!systemPrompt.isNullOrBlank()) {
                append(systemPrompt.trim())
            } else if (prompt.contains("<|im_start|>system")) {
                val sysPart = prompt.substringAfter("<|im_start|>system\n")
                    .substringBefore("<|im_end|>")
                    .trim()
                if (sysPart.isNotEmpty()) append(sysPart)
            } else {
                append("Eres Silf, un asistente útil y preciso.")
            }
            if (screenSnapshot.isNotEmpty() && !contains(screenSnapshot)) {
                append("\nInformación de la pantalla actual del usuario:\n$screenSnapshot")
            }
        }.trim()

        val cleanPrompt = if (prompt.contains("<|im_start|>system")) {
            prompt.substringAfter("<|im_end|>\n").trim()
        } else {
            prompt.trim()
        }

        // Construir JSON con formato Ollama (/api/generate)
        // CRÍTICO: "stream": false para evitar abortos prematuros de socket
        val json = JSONObject().apply {
            put("model", model)
            put("stream", false)
            if (effectiveSystemPrompt.isNotEmpty()) {
                put("system", effectiveSystemPrompt)
            }
            put("prompt", cleanPrompt)
        }

        Log.d(TAG, "Enviando POST (stream=false) a $endpoint con modelo: $model")

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

                    try {
                        val responseBody = resp.body?.string().orEmpty()
                        if (responseBody.isBlank()) {
                            trySend("⚠️ Respuesta vacía de Ollama.")
                            close()
                            return
                        }

                        val jsonResp = JSONObject(responseBody)
                        val text = when {
                            jsonResp.has("response") -> jsonResp.getString("response")
                            jsonResp.has("message") -> {
                                val messageObj = jsonResp.optJSONObject("message")
                                messageObj?.optString("content").orEmpty()
                            }
                            jsonResp.has("error") -> "Error de Ollama: ${jsonResp.getString("error")}"
                            else -> responseBody
                        }

                        if (text.isNotEmpty()) {
                            trySend(text)
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            Log.e(TAG, "Error procesando respuesta de Ollama", e)
                            trySend("⚠️ Error al parsear respuesta: ${e.message}")
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
        return preferencesManager.endpointUrl.value.isNotBlank()
    }

    override fun unload() {
        stopGeneration()
    }

    companion object {
        private const val TAG = "ApiEngine"
    }
}
