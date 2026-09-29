package com.silf.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silf.app.data.repository.ChatRepository
import com.silf.app.domain.llm.LlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val llmEngine: LlmEngine,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _showErrorToast = MutableStateFlow<String?>(null)
    val showErrorToast: StateFlow<String?> = _showErrorToast.asStateFlow()

    private var generationJob: Job? = null

    init {
        // Carga el historial persistido desde Room al iniciar
        viewModelScope.launch {
            chatRepository.getAllMessages().collect { entities ->
                // Solo actualizar si no hay generación activa para no interferir con el stream
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

        generationJob = viewModelScope.launch {
            // Guardar mensaje del usuario en Room
            chatRepository.insertMessage(text = prompt, isUser = true)

            val responseId = UUID.randomUUID().toString()
            // Agregar placeholder de respuesta en UI (Room lo actualizará al finalizar)
            _messages.value = _messages.value + ChatMessage(id = responseId, text = "", isUser = false)

            val builder = StringBuilder()
            val stopTokens = listOf("<|im_end|>", "<|endoftext|>")
            try {
                llmEngine.generateResponseStream(prompt).collect { token ->
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
                // Guardar respuesta completa en Room al terminar la generación
                val finalResponse = builder.toString()
                if (finalResponse.isNotEmpty()) {
                    chatRepository.insertMessage(text = finalResponse, isUser = false)
                }
            } catch (c: CancellationException) {
                // Guardar respuesta parcial si fue cancelada por el usuario
                val partialResponse = builder.toString()
                if (partialResponse.isNotEmpty()) {
                    chatRepository.insertMessage(text = partialResponse, isUser = false)
                }
                throw c
            } catch (t: Throwable) {
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

