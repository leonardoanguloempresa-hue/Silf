package com.silf.app.ui.screens.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Catálogo de Modelos",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp, top = 16.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(viewModel.availableModels) { entry ->
                val state = downloadStates[entry.id] ?: DownloadState.NotStarted
                ModelCard(
                    entry = entry,
                    state = state,
                    isLoading = isLoadingModel == entry.id,
                    onDownloadClick = { viewModel.startDownload(entry.downloadSpec) },
                    onCancelClick = { viewModel.cancelDownload(entry.id) },
                    onLoadClick = { path -> viewModel.loadModel(entry.id, path) }
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    state: DownloadState,
    isLoading: Boolean,
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
                entry.requiresLicense -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Requiere licencia (HuggingFace)")
                    }
                }
                state is DownloadState.Completed -> {
                    if (isLoading) {
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cargando modelo...")
                        }
                    } else {
                        Button(onClick = { onLoadClick(state.filePath) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Text("Cargar Modelo en RAM", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Text(text = "Descargado · Uso pendiente Fase 5", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
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

private fun formatProgress(bytes: Long, total: Long?): String {
    val mb = bytes / (1024 * 1024)
    val totalMb = if (total != null && total > 0) "${total / (1024 * 1024)} MB" else "?? MB"
    return "$mb MB / $totalMb"
}
