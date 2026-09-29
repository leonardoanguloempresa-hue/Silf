package com.silf.app.ui.screens.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silf.app.SilfApplication
import com.silf.app.domain.catalog.ModelEntry
import com.silf.app.domain.download.DownloadState

@Composable
fun CatalogScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContainer = (context.applicationContext as SilfApplication).container

    val factory = remember { CatalogViewModel.Factory(appContainer.downloadRepository, appContainer.llmEngine) }
    val viewModel: CatalogViewModel = viewModel(factory = factory)

    val downloadStates by viewModel.downloadStates.collectAsState()
    val isLoadingModel by viewModel.isLoadingModel.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val ollamaUrl by viewModel.ollamaUrl.collectAsState()
    val ollamaStatus by viewModel.ollamaStatus.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Catálogo de Modelos",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp, top = 16.dp)
        )

        errorMessage?.let { errorMsg ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("Cerrar", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Tarjetas de modelos locales GGUF
            items(viewModel.availableModels) { entry ->
                val state = downloadStates[entry.id] ?: DownloadState.NotStarted
                ModelCard(
                    entry = entry,
                    state = state,
                    isLoading = isLoadingModel == entry.id,
                    isActive = activeModelId == entry.id,
                    onDownloadClick = { viewModel.startDownload(entry.downloadSpec) },
                    onCancelClick = { viewModel.cancelDownload(entry.id) },
                    onLoadClick = { path -> viewModel.loadModel(entry.id, path) }
                )
            }
            // Tarjeta de conexión Ollama
            item {
                OllamaCard(
                    url = ollamaUrl,
                    status = ollamaStatus,
                    onUrlChanged = viewModel::onOllamaUrlChanged,
                    onConnectClick = viewModel::connectToOllama
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tarjeta de modelo local (GGUF)
// ---------------------------------------------------------------------------

@Composable
private fun ModelCard(
    entry: ModelEntry,
    state: DownloadState,
    isLoading: Boolean,
    isActive: Boolean,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onLoadClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = entry.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = entry.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            Text(text = "Tamaño: ${entry.sizeLabel}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

            Spacer(modifier = Modifier.height(12.dp))

            when {
                state is DownloadState.Completed -> {
                    when {
                        isActive -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = Color(0xFF4CAF50),
                                    disabledContentColor = Color.White
                                )
                            ) {
                                Text("Modelo Activo", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        isLoading -> {
                            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cargando modelo...")
                            }
                        }
                        else -> {
                            Button(
                                onClick = { onLoadClick(state.filePath) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Cargar Modelo en RAM", color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Text(
                        text = if (isActive) "Modelo activo y listo para usar" else "Descargado · Listo para cargar",
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                state is DownloadState.InProgress -> {
                    val progress = state.progress?.div(100f) ?: 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = MaterialTheme.colorScheme.primary)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = formatProgress(state.bytesDownloaded, state.totalBytes), color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = onCancelClick) { Text("Cancelar", color = Color.Red) }
                    }
                }
                else -> {
                    Button(onClick = onDownloadClick, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Text("Descargar modelo", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tarjeta de conexión Ollama (modo híbrido)
// ---------------------------------------------------------------------------

@Composable
private fun OllamaCard(
    url: String,
    status: OllamaStatus,
    onUrlChanged: (String) -> Unit,
    onConnectClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Conectar a Ollama PC",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Modo híbrido · Usa un modelo en tu PC mediante Ollama. La app se conecta a través de tu red local Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChanged,
                label = { Text("Dirección IP del servidor") },
                placeholder = { Text("http://192.168.1.XX:11434") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = status !is OllamaStatus.Connecting
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Botón de conexión con estado visual
            when (status) {
                is OllamaStatus.Connecting -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Conectando...")
                    }
                }
                is OllamaStatus.Connected -> {
                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("✓ Conectado — Reconectar", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Ollama activo en: ${status.url}",
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                is OllamaStatus.Error -> {
                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Conectar")
                    }
                    Text(
                        text = status.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                else -> {
                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Conectar")
                    }
                }
            }
        }
    }
}

private fun formatProgress(bytes: Long, total: Long?): String {
    val mb = bytes / (1024 * 1024)
    val totalMb = if (total != null && total > 0) "${total / (1024 * 1024)} MB" else "?? MB"
    return "$mb MB / $totalMb"
}
