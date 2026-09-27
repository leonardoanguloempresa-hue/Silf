package com.silf.app.ui.chat
sealed interface ChatEvent {
    data class OnMessageChange(val message: String) : ChatEvent
    object OnSendMessage : ChatEvent
}
