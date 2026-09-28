package com.silf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silf.app.SilfApplication
import com.silf.app.ui.chat.ChatMessage
import com.silf.app.ui.chat.ChatViewModel
import com.silf.app.ui.chat.ChatViewModelFactory
import com.silf.app.ui.theme.SilfCardSurface

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as SilfApplication
    val container = application.container
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(container.llmEngine))

    val messages by chatViewModel.messages.collectAsState()
    val draftText by chatViewModel.draftText.collectAsState()
    val isGenerating by chatViewModel.isGenerating.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = { chatViewModel.clearHistory() }) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Limpiar historial", tint = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(240.dp)
                .border(1.dp, Color.Gray.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "Placeholder Rive", color = Color.Gray)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draftText,
                onValueChange = chatViewModel::onDraftChanged,
                placeholder = { Text("Escribe un mensaje...", color = Color.Gray) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray
                ),
                keyboardActions = KeyboardActions(onSend = { chatViewModel.onSendMessage() }),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { chatViewModel.onSendMessage() },
                enabled = draftText.isNotBlank() && !isGenerating
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar",
                    tint = if (draftText.isNotBlank() && !isGenerating) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val arrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    val backgroundColor = if (message.isUser) SilfCardSurface else Color.Transparent

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = arrangement
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                color = Color.White
            )
        }
    }
}
