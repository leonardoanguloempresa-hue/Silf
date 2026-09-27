package com.silf.app.ui.chat
data class ChatMessage(val text: String, val isFromUser: Boolean)
data class ChatUiState(
    val messages: List<ChatMessage> = listOf(ChatMessage("Hola, soy Silf", false)),
    val currentInput: String = ""
)
