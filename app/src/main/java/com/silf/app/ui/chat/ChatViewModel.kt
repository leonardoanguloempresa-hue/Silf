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
            val clickRegex = Regex("""\[CLICK:\s*(\d+)\]?""", RegexOption.IGNORE_CASE)
            var clickHandled = false
            var clickedId: Int? = null

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

                        // Intercepción: Detección temprana de la cadena [CLICK:
                        val match = clickRegex.find(currentText)
                        if (match != null) {
                            val id = match.groupValues[1].toIntOrNull()
                            if (id != null && !clickHandled) {
                                clickHandled = true
                                clickedId = id
                                SilfAccessibilityService.instance?.performClickOnNode(id)
                                stopGeneration()
                                return@collect
                            }
                        }

                        // Limpiar comando de la vista en tiempo real para no mostrar comandos de control en UI
                        val textToDisplay = currentText.replace(Regex("""\[CLICK:\s*\d*\]?""", RegexOption.IGNORE_CASE), "").trim()
                        if (textToDisplay.isNotEmpty()) {
                            _messages.value = _messages.value.map {
                                if (it.id == responseId) it.copy(text = textToDisplay) else it
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

            val fullGenerated = builder.toString()

            // Si no se interceptó durante el streaming, interceptar al finalizar
            if (!clickHandled) {
                val match = clickRegex.find(fullGenerated)
                if (match != null) {
                    val id = match.groupValues[1].toIntOrNull()
                    if (id != null) {
                        clickHandled = true
                        clickedId = id
                        SilfAccessibilityService.instance?.performClickOnNode(id)
                    }
                }
            }

            val remainingText = fullGenerated.replace(Regex("""\[CLICK:\s*\d+\]?""", RegexOption.IGNORE_CASE), "").trim()
            val textToDisplay = when {
                remainingText.isNotEmpty() -> remainingText
                clickHandled && clickedId != null -> "Pulsando elemento [$clickedId]"
                else -> fullGenerated.trim()
            }

            _messages.value = _messages.value.map {
                if (it.id == responseId) it.copy(text = textToDisplay) else it
            }
            if (textToDisplay.isNotEmpty()) {
                chatRepository.insertMessage(text = textToDisplay, isUser = false)
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
