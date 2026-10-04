package com.silf.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silf.app.accessibility.SilfAccessibilityService
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
    private val screenSnapshotFlow: StateFlow<String> = SilfAccessibilityService.screenSnapshot
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
            _showErrorToast.value = "Carga un modelo en el catálogo primero"
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

            // Construir el prompt ChatML multi-turno con historial desde Room (últimos 10 mensajes)
            val history = chatRepository.getLastMessages(limit = 10)

            val currentSnapshot = screenSnapshotFlow.value.trim()

            val chatMlPrompt = buildString {
                append("<|im_start|>system\nEres Silf, un asistente útil y preciso.<|im_end|>\n")
                if (currentSnapshot.isNotEmpty()) {
                    append("<|im_start|>system\nInformación de la pantalla actual del usuario:\n$currentSnapshot<|im_end|>\n")
                }
                // Formatear historial con etiquetas exactas: user y assistant
                for (entity in history) {
                    val role = if (entity.isUser) "user" else "assistant"
                    append("<|im_start|>$role\n${entity.text.trim()}<|im_end|>\n")
                }
                // Abrir el turno del asistente para la generación
                append("<|im_start|>assistant\n")
            }

            val builder = StringBuilder()
            val stopTokens = listOf("<|im_end|>", "<|endoftext|>")
            try {
                llmEngine.generateResponseStream(chatMlPrompt).collect { token ->
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
                        _messages.value = _messages.value.map {
                            if (it.id == responseId) it.copy(text = builder.toString()) else it
                        }
                    }
                }
                val finalResponse = builder.toString().trim()
                _isGenerating.value = false
                if (finalResponse.isNotEmpty()) {
                    chatRepository.insertMessage(text = finalResponse, isUser = false)
                }
            } catch (c: CancellationException) {
                _isGenerating.value = false
                val partialResponse = builder.toString().trim()
                if (partialResponse.isNotEmpty()) {
                    chatRepository.insertMessage(text = partialResponse, isUser = false)
                }
                throw c
            } catch (t: Throwable) {
                _isGenerating.value = false
                _messages.value = _messages.value.map {
                    if (it.id == responseId) it.copy(text = "Error: ${t.message}") else it
                }
            } finally {
                _isGenerating.value = false
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
