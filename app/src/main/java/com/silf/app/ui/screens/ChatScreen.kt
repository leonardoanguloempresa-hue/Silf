package com.silf.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silf.app.SilfApplication
import com.silf.app.ui.chat.ChatMessage
import com.silf.app.ui.chat.ChatViewModel
import com.silf.app.ui.chat.ChatViewModelFactory
import com.silf.app.ui.theme.SilfCardSurface

private val EyeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Eye",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            close()
            moveTo(12f, 17f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
            reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
            reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
            close()
            moveTo(12f, 9f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
            reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
            close()
        }
    }.build()
}

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Barra superior con estado del modelo y botón para limpiar historial
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    text = if (container.llmEngine.isReady()) "Modelo Local Activo" else "Sin modelo cargado en RAM",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(onClick = { chatViewModel.clearHistory() }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Limpiar historial",
                    tint = Color.LightGray
                )
            }
        }

        // Avatar animado de Silf
        SilfAvatar(
            isGenerating = isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(200.dp)
        )

        // Lista de mensajes del chat
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        // Indicador de Visión activada (Servicio de accesibilidad leyendo UI)
        AnimatedVisibility(
            visible = isVisionActive,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = EyeIcon,
                    contentDescription = "Visión activada",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Visión activada (Servicio de Accesibilidad conectado)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Mensaje de error/aviso si el modelo no está cargado
        if (showErrorToast != null) {
            Text(
                text = showErrorToast ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }

        // Barra inferior de entrada de texto
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draftText,
                onValueChange = chatViewModel::onDraftChanged,
                placeholder = { Text("Escribe un mensaje o petición...", color = Color.Gray) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                    focusedContainerColor = SilfCardSurface,
                    unfocusedContainerColor = SilfCardSurface.copy(alpha = 0.6f)
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

@Composable
fun MessageBubble(message: ChatMessage) {
    val arrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    val backgroundColor = if (message.isUser) SilfCardSurface else Color.Black.copy(alpha = 0.35f)

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
