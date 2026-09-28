package com.silf.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silf.app.domain.llm.LlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(private val llmEngine: LlmEngine) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _showErrorToast = MutableStateFlow<String?>(null)
    val showErrorToast: StateFlow<String?> = _showErrorToast.asStateFlow()

    private var generationJob: Job? = null

    fun onDraftChanged(text: String) { _draftText.value = text }

    fun dismissError() { _showErrorToast.value = null }

    fun onSendMessage() {
        val prompt = _draftText.value.trim()
        if (prompt.isEmpty() || _isGenerating.value) return

        if (!llmEngine.isReady()) {
            _showErrorToast.value = "Carga un modelo en el catálogo primero"
            return
        }

        _messages.value = _messages.value + ChatMessage(text = prompt, isUser = true)
        _draftText.value = ""
        val responseId = UUID.randomUUID().toString()
        _messages.value = _messages.value + ChatMessage(id = responseId, text = "", isUser = false)
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            val builder = StringBuilder()
            try {
                llmEngine.generateResponseStream(prompt).collect { token ->
                    builder.append(token)
                    _messages.value = _messages.value.map {
                        if (it.id == responseId) it.copy(text = builder.toString()) else it
                    }
                }
            } catch (c: CancellationException) {
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
    }

    override fun onCleared() {
        super.onCleared()
        stopGeneration()
    }
}
