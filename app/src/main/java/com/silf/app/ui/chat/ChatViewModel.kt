package com.silf.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silf.app.accessibility.SilfAccessibilityService
import com.silf.app.data.preferences.PreferencesManager
import com.silf.app.data.repository.ChatRepository
import com.silf.app.domain.llm.LlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val llmEngine: LlmEngine,
    private val chatRepository: ChatRepository,
    private val screenSnapshotFlow: StateFlow<String> = SilfAccessibilityService.screenSnapshot,
    private val preferencesManager: PreferencesManager? = null
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _showErrorToast = MutableStateFlow<String?>(null)
    val showErrorToast: StateFlow<String?> = _showErrorToast.asStateFlow()

    val screenSnapshot: StateFlow<String> = screenSnapshotFlow

    val isVisionActive: StateFlow<Boolean> = screenSnapshotFlow
        .map { it.isNotBlank() && SilfAccessibilityService.isEnabled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = screenSnapshotFlow.value.isNotBlank() && SilfAccessibilityService.isEnabled
        )

    val endpointUrl: StateFlow<String> =
        preferencesManager?.endpointUrl ?: MutableStateFlow(PreferencesManager.DEFAULT_ENDPOINT_URL)
    val modelName: StateFlow<String> =
        preferencesManager?.modelName ?: MutableStateFlow(PreferencesManager.DEFAULT_MODEL_NAME)

    fun updateEndpointUrl(url: String) {
        preferencesManager?.setEndpointUrl(url)
    }

    fun updateModelName(model: String) {
        preferencesManager?.setModelName(model)
    }

    private var generationJob: Job? = null

    init {
        // Carga el historial persistido desde Room al iniciar
        viewModelScope.launch {
            chatRepository.getAllMessages().collect { entities ->
                // Solo sincronizar si no hay generación activa para no pisar el stream
                if (!_isGenerating.value) {
                    _messages.value = entities.map { entity ->
                        ChatMessage(
                            id = entity.id.toString(),
                            text = entity.text,
                            isUser = entity.isUser
                        )
                    }
                }
            }
        }
    }

    fun onDraftChanged(text: String) { _draftText.value = text }

    fun dismissError() { _showErrorToast.value = null }

    fun onSendMessage() {
        val prompt = _draftText.value.trim()
        if (prompt.isEmpty() || _isGenerating.value) return

        if (!llmEngine.isReady()) {
            _showErrorToast.value = "Por favor carga un modelo GGUF en la sección de Modelos"
            return
        }

        _draftText.value = ""
        _isGenerating.value = true

        val userMessageId = UUID.randomUUID().toString()
        val responseId = UUID.randomUUID().toString()

        // Mostrar de inmediato en UI el mensaje del usuario y el contenedor de la respuesta
        _messages.value = _messages.value +
                ChatMessage(id = userMessageId, text = prompt, isUser = true) +
                ChatMessage(id = responseId, text = "", isUser = false)

        generationJob = viewModelScope.launch {
            // Guardar mensaje del usuario en Room (solo el texto del usuario)
            chatRepository.insertMessage(text = prompt, isUser = true)

            // Obtener historial reciente desde Room (últimos 10 mensajes)
            val history = chatRepository.getLastMessages(limit = 10)

            val currentSnapshot = screenSnapshotFlow.value.trim()

            // System Prompt para el Agente con snapshot de pantalla y reglas de clic
            val systemPrompt = buildString {
                append("Eres un agente que controla el teléfono. ")
                if (currentSnapshot.isNotEmpty()) {
                    append("Ves esta pantalla:\n").append(currentSnapshot).append("\n")
                }
                append("Para hacer clic en un elemento, debes responder ÚNICAMENTE con el comando [CLICK: número_de_índice].")
            }

            // Construir el prompt de turnos para el modelo
            val conversationPrompt = buildString {
                for (entity in history) {
                    val role = if (entity.isUser) "user" else "assistant"
                    append("<|im_start|>$role\n${entity.text.trim()}<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }

            val builder = StringBuilder()
            val stopTokens = listOf("<|im_end|>", "<|endoftext|>")
            val clickRegex = Regex("""\[CLICK:\s*(\d+)\]""", RegexOption.IGNORE_CASE)

            try {
                llmEngine.generateResponseStream(conversationPrompt, systemPrompt).collect { token ->
                    if (stopTokens.any { token.contains(it) }) {
                        stopGeneration()
                        return@collect
                    }
                    var cleanToken = token
                    for (stop in stopTokens) {
                        cleanToken = cleanToken.replace(stop, "")
                    }
                    if (cleanToken.isNotEmpty()) {
                        builder.append(cleanToken)
                        val currentText = builder.toString()

                        // Si ya se generó el comando completo de click, detener generación
                        if (clickRegex.containsMatchIn(currentText)) {
                            stopGeneration()
                            return@collect
                        }

                        // Limpiar comando de la vista en tiempo real para no mostrarlo
                        val textToDisplay = currentText.replace(clickRegex, "").trim()
                        _messages.value = _messages.value.map {
                            if (it.id == responseId) it.copy(text = textToDisplay) else it
                        }
                    }
                }
            } catch (c: CancellationException) {
                // Cancelación esperada al detener generación
            } catch (t: Throwable) {
                _showErrorToast.value = "Error: ${t.message}"
            } finally {
                _isGenerating.value = false
            }

            val fullGenerated = builder.toString()
            val clickMatch = clickRegex.find(fullGenerated)

            if (clickMatch != null) {
                val nodeIndex = clickMatch.groupValues[1].toIntOrNull()
                if (nodeIndex != null) {
                    // Ejecutar el clic en el servicio de accesibilidad
                    val clickSuccess = SilfAccessibilityService.performClick(nodeIndex)

                    // Extraer y no mostrar el comando [CLICK: X] en el chat
                    val remainingText = fullGenerated.replace(clickRegex, "").trim()
                    val textToDisplay = if (remainingText.isNotEmpty()) {
                        remainingText
                    } else {
                        if (clickSuccess) "Clic ejecutado en elemento [$nodeIndex]" else "No se pudo pulsar el elemento [$nodeIndex]"
                    }

                    _messages.value = _messages.value.map {
                        if (it.id == responseId) it.copy(text = textToDisplay) else it
                    }
                    chatRepository.insertMessage(text = textToDisplay, isUser = false)
                } else {
                    val remainingText = fullGenerated.replace(clickRegex, "").trim()
                    _messages.value = _messages.value.map {
                        if (it.id == responseId) it.copy(text = remainingText) else it
                    }
                    if (remainingText.isNotEmpty()) {
                        chatRepository.insertMessage(text = remainingText, isUser = false)
                    }
                }
            } else {
                val finalResponse = fullGenerated.trim()
                _messages.value = _messages.value.map {
                    if (it.id == responseId) it.copy(text = finalResponse) else it
                }
                if (finalResponse.isNotEmpty()) {
                    chatRepository.insertMessage(text = finalResponse, isUser = false)
                }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        llmEngine.stopGeneration()
        _isGenerating.value = false
    }

    fun clearHistory() {
        stopGeneration()
        _messages.value = emptyList()
        _draftText.value = ""
        viewModelScope.launch {
            chatRepository.clearHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopGeneration()
    }
}
