package com.silf.app.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val keyboardController = LocalSoftwareKeyboardController.current
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

    LaunchedEffect(Unit) {
        chatViewModel.closeUiEvent.collect {
            activity?.finish()
        }
    }

    // Contenedor raíz transparente
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Tocar fuera del panel inferior cierra el asistente flotante sin bloquear accesibilidad
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { activity?.finish() }
                }
        )

        // Panel inferior flotante tipo BottomSheet compacto (solo ocupa el tercio inferior)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = SilfCardSurface.copy(alpha = 0.96f),
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Indicador de arrastre (drag handle)
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.4f))
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Encabezado compacto
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (container.llmEngine.isReady()) Color(0xFF00E676) else Color(0xFFFF9100))
                        )
                        Text(
                            text = if (container.llmEngine.isReady()) "Silf Asistente" else "Silf (Sin modelo)",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { chatViewModel.clearHistory() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Limpiar historial",
                                tint = Color.LightGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { activity?.finish() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar Asistente",
                                tint = Color.LightGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Pequeño historial (máximo 90.dp) dejando visible el resto de la pantalla
                if (messages.isNotEmpty()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 90.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(messages) { message ->
                            MessageBubble(message)
                        }
                    }
                }

                // Indicador de Visión activa (pantalla capturada)
                AnimatedVisibility(
                    visible = isVisionActive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
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
                            text = "Visión activa · Pantalla capturada",
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
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Barra de chat inferior
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = chatViewModel::onDraftChanged,
                        placeholder = { Text("Escribe una orden...", color = Color.Gray) },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                            focusedContainerColor = Color.Black.copy(alpha = 0.35f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.2f)
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            keyboardController?.hide()
                            chatViewModel.onSendMessage()
                        }),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            keyboardController?.hide()
                            chatViewModel.onSendMessage()
                        },
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
