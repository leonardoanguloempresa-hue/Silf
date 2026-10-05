package com.silf.app.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silf.app.SilfApplication
import com.silf.app.ui.chat.ChatViewModel
import com.silf.app.ui.chat.ChatViewModelFactory
import com.silf.app.ui.theme.SilfCardSurface

@Composable
fun AssistantScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val application = context.applicationContext as SilfApplication
    val container = application.container
    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(container.llmEngine, container.chatRepository, container.preferencesManager, context = application)
    )

    val messages by chatViewModel.messages.collectAsState()
    val draftText by chatViewModel.draftText.collectAsState()
    val isGenerating by chatViewModel.isGenerating.collectAsState()
    val isVisionActive by chatViewModel.isVisionActive.collectAsState()
    val showErrorToast by chatViewModel.showErrorToast.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Contenedor raíz completamente transparente
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Tocar fuera del panel inferior cierra el asistente flotante
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    activity?.finish()
                }
        )

        // Panel inferior flotante tipo BottomSheet
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = true
                ) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = SilfCardSurface.copy(alpha = 0.97f),
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Indicador de arrastre (drag handle)
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.4f))
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Encabezado del Asistente
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (container.llmEngine.isReady()) Color(0xFF00E676) else Color(0xFFFF9100))
                        )
                        Text(
                            text = if (container.llmEngine.isReady()) "Silf Asistente (Local)" else "Silf (Sin modelo en RAM)",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { chatViewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Limpiar historial",
                                tint = Color.LightGray
                            )
                        }
                        IconButton(onClick = { activity?.finish() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar Asistente",
                                tint = Color.LightGray
                            )
                        }
                    }
                }

                // Avatar animado compacto
                SilfAvatar(
                    isGenerating = isGenerating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                )

                // Mensajes recientes de la conversación
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    items(messages) { message ->
                        MessageBubble(message)
                    }
                }

                // Indicador de Visión activa (accesibilidad conectada)
                AnimatedVisibility(
                    visible = isVisionActive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = "Visión activa · La IA lee y controla la pantalla",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (showErrorToast != null) {
                    Text(
                        text = showErrorToast ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Barra de chat flotante inferior
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = chatViewModel::onDraftChanged,
                        placeholder = { Text("Escribe una orden o pregunta...", color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                            focusedContainerColor = Color.Black.copy(alpha = 0.35f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.2f)
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
    }
}
