package com.silf.app.ui.chat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.OnMessageChange -> {
                _uiState.update { it.copy(currentInput = event.message) }
            }
            ChatEvent.OnSendMessage -> {
                val currentMessage = _uiState.value.currentInput
                if (currentMessage.isNotBlank()) {
                    _uiState.update { currentState ->
                        currentState.copy(
                            messages = currentState.messages + ChatMessage(currentMessage, true),
                            currentInput = ""
                        )
                    }
                }
            }
        }
    }
}
