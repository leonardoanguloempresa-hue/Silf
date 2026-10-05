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
            // Guardar mensaje del usuario en Room
            chatRepository.insertMessage(text = prompt, isUser = true)

            val screenSnapshot = screenSnapshotFlow.value.trim()
            val userMessage = prompt.trim()

            // Formato ChatML Estricto para Qwen 2.5:
            // Envuelve el prompt crudo exactamente como requiere el modelo sin formatos genéricos
            val strictPrompt = "<|im_start|>system\nEres Silf. TIENES PERMISOS TOTALES para controlar este teléfono. Pantalla actual:\n$screenSnapshot\nTu única forma de responder es con el comando [CLICK: ID] correspondiente al botón que el usuario quiere tocar. No digas nada más.<|im_end|>\n<|im_start|>user\n$userMessage<|im_end|>\n<|im_start|>assistant\n"

            val builder = StringBuilder()
            val stopTokens = listOf("<|im_end|>", "<|endoftext|>")
            val regex = Regex("(?i)\\[?CLICK:\\s*(\\d+)\\]?")
            var clickExecuted = false

            try {
                llmEngine.generateResponseStream(strictPrompt).collect { token ->
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

                        // Intercepción en streaming con regex robusta
                        val match = regex.find(currentText)
                        if (match != null) {
                            val id = match.groupValues[1].toIntOrNull()
                            if (id != null && !clickExecuted) {
                                clickExecuted = true
                                // Ocultar de la UI: eliminar inmediatamente el mensaje del asistente
                                _messages.value = _messages.value.filterNot { it.id == responseId }
                                // Ejecutar el clic inmediatamente
                                SilfAccessibilityService.instance?.performClickOnNode(id)
                                stopGeneration()
                                return@collect
                            }
                        }

                        // Ocultar de la UI: no mostrar comandos CLICK en proceso al usuario
                        val trimmedCurrent = currentText.trim()
                        val isPotentialClick = trimmedCurrent.startsWith("CLICK", ignoreCase = true) ||
                                              trimmedCurrent.startsWith("[CLICK", ignoreCase = true) ||
                                              trimmedCurrent.startsWith("[", ignoreCase = true)

                        if (!isPotentialClick && !clickExecuted) {
                            _messages.value = _messages.value.map {
                                if (it.id == responseId) it.copy(text = currentText.trim()) else it
                            }
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

            val fullGenerated = builder.toString().trim()
            val match = regex.find(fullGenerated)

            if (match != null) {
                // Coincide con la Regex del comando CLICK
                val id = match.groupValues[1].toIntOrNull()
                // Ocultar de la UI: NO añadirlo / eliminarlo de la lista de mensajes de la UI
                _messages.value = _messages.value.filterNot { it.id == responseId }

                // Ejecutar el clic inmediatamente si no se ejecutó durante el streaming
                if (!clickExecuted && id != null) {
                    clickExecuted = true
                    SilfAccessibilityService.instance?.performClickOnNode(id)
                }
            } else {
                // No es comando CLICK: respuesta conversacional normal
                if (fullGenerated.isNotEmpty()) {
                    _messages.value = _messages.value.map {
                        if (it.id == responseId) it.copy(text = fullGenerated) else it
                    }
                    chatRepository.insertMessage(text = fullGenerated, isUser = false)
                } else {
                    _messages.value = _messages.value.filterNot { it.id == responseId }
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
