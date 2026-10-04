package com.silf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silf.app.SilfApplication
import com.silf.app.data.preferences.PreferencesManager
import com.silf.app.ui.theme.SilfCardSurface

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as SilfApplication
    val prefs = application.container.preferencesManager

    val currentUrl by prefs.endpointUrl.collectAsState()
    val currentModel by prefs.modelName.collectAsState()

    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    var modelInput by remember(currentModel) { mutableStateOf(currentModel) }
    var savedFeedback by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Configuración del Servidor",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Silf funciona como Cliente ligero. El modelo se ejecuta en tu PC en la red local Wi-Fi mediante Ollama (u otro servidor HTTP compatible). No requiere descargar archivos pesados al teléfono.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SilfCardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Conexión Wi-Fi / LAN",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        savedFeedback = false
                    },
                    label = { Text("URL del Endpoint (PC)") },
                    placeholder = { Text(PreferencesManager.DEFAULT_ENDPOINT_URL) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = modelInput,
                    onValueChange = {
                        modelInput = it
                        savedFeedback = false
                    },
                    label = { Text("Nombre del Modelo (Ollama)") },
                    placeholder = { Text(PreferencesManager.DEFAULT_MODEL_NAME) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            urlInput = PreferencesManager.DEFAULT_ENDPOINT_URL
                            modelInput = PreferencesManager.DEFAULT_MODEL_NAME
                            prefs.setEndpointUrl(urlInput)
                            prefs.setModelName(modelInput)
                            savedFeedback = true
                        }
                    ) {
                        Text("Restablecer", color = Color.Gray)
                    }

                    Button(
                        onClick = {
                            prefs.setEndpointUrl(urlInput)
                            prefs.setModelName(modelInput)
                            savedFeedback = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                if (savedFeedback) {
                    Text(
                        text = "✓ Configuración guardada en SharedPreferences",
                        color = Color(0xFF00E676),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SilfCardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Instrucciones Rápidas",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "1. En tu PC, abre una terminal y ejecuta Ollama con acceso de red:\n   OLLAMA_HOST=0.0.0.0 ollama run qwen2.5:3b\n2. Asegúrate de que el teléfono y la PC estén en la misma red Wi-Fi.\n3. Configura la IP local de tu PC en el campo superior (ej. http://192.168.1.80:11434/api/generate).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        }
    }
}
