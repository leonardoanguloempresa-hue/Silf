package com.silf.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silf.app.SilfApplication
import com.silf.app.accessibility.SilfAccessibilityService
import com.silf.app.ui.theme.SilfCardSurface
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as SilfApplication
    val container = application.container
    val coroutineScope = rememberCoroutineScope()

    var historyClearedFeedback by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Ajustes de Silf",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Configuración del asistente local Silf, permisos del sistema y motor de inferencia en dispositivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        // Tarjeta 1: Servicio de Accesibilidad (Visión y Manos de la IA)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SilfCardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Servicio de Accesibilidad",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (SilfAccessibilityService.isEnabled) Color(0xFF00E676) else Color(0xFFFF5252))
                        )
                        Text(
                            text = if (SilfAccessibilityService.isEnabled) "Habilitado" else "Deshabilitado",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (SilfAccessibilityService.isEnabled) Color(0xFF00E676) else Color(0xFFFF5252),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "El servicio de accesibilidad permite a Silf leer los elementos de la pantalla (Visión) y ejecutar acciones de clic automáticas cuando emite un comando [CLICK: X].",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )

                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Abrir Ajustes de Accesibilidad", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Tarjeta 2: Motor LLM Local (Llama.cpp en RAM)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SilfCardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Motor LLM Local (Llama.cpp)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (container.llmEngine.isReady()) Color(0xFF00E676) else Color(0xFFFF9100))
                        )
                        Text(
                            text = if (container.llmEngine.isReady()) "Cargado en RAM" else "Sin modelo",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (container.llmEngine.isReady()) Color(0xFF00E676) else Color(0xFFFF9100),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Silf opera con inferencia 100% offline y privada en tu dispositivo móvil. Los modelos en formato GGUF se cargan en la memoria RAM y se procesan nativamente con C++.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )

                Text(
                    text = "Para descargar o cargar un modelo GGUF en memoria, ve a la pestaña 'Modelos'. Una vez cargado, permanecerá listo en RAM para la ventana del Asistente.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Tarjeta 3: Configurar Asistente Predeterminado (Movicom)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SilfCardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Asistente Predeterminado del Sistema",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Puedes activar la ventana flotante de Silf Assistant manteniendo presionado el botón de inicio o deslizando desde las esquinas inferiores.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )

                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Configurar Asistente en Android", color = Color.White)
                }
            }
        }

        // Tarjeta 4: Historial de Conversación
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SilfCardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Historial de Mensajes",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Los mensajes se almacenan en una base de datos local SQLite (Room) para dar continuidad a las conversaciones con el asistente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )

                Button(
                    onClick = {
                        coroutineScope.launch {
                            container.chatRepository.clearHistory()
                            historyClearedFeedback = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Borrar Historial de Chat", color = Color.White, fontWeight = FontWeight.Bold)
                }

                if (historyClearedFeedback) {
                    Text(
                        text = "✓ Historial eliminado correctamente",
                        color = Color(0xFF00E676),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
