package com.silf.app.ui.screens.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.silf.app.domain.catalog.ModelCatalog
import com.silf.app.domain.catalog.ModelEntry
import com.silf.app.domain.download.DownloadRepository
import com.silf.app.domain.download.DownloadState
import com.silf.app.domain.download.ModelDownloadSpec
import com.silf.app.data.workers.ModelDownloadContract
import com.silf.app.domain.llm.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de conexión a un servidor Ollama remoto. */
sealed class OllamaStatus {
    object Idle : OllamaStatus()
    object Connecting : OllamaStatus()
    data class Connected(val url: String) : OllamaStatus()
    data class Error(val message: String) : OllamaStatus()
}

class CatalogViewModel(
    private val repository: DownloadRepository,
    private val llmEngine: LlmEngine
) : ViewModel() {

    val availableModels: List<ModelEntry> = ModelCatalog.models
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val _isLoadingModel = MutableStateFlow<String?>(null)
    val isLoadingModel: StateFlow<String?> = _isLoadingModel.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // --- Estado Ollama ---
    private val _ollamaUrl = MutableStateFlow("http://192.168.1.1:11434")
    val ollamaUrl: StateFlow<String> = _ollamaUrl.asStateFlow()

    private val _ollamaStatus = MutableStateFlow<OllamaStatus>(OllamaStatus.Idle)
    val ollamaStatus: StateFlow<OllamaStatus> = _ollamaStatus.asStateFlow()

    fun onOllamaUrlChanged(url: String) { _ollamaUrl.value = url }

    /**
     * Intenta conectar a un servidor Ollama en la URL configurada.
     * La lógica HTTP completa se implementará en la siguiente fase.
     * Por ahora simula el estado Connecting → Connected/Error.
     */
    fun connectToOllama() {
        val url = _ollamaUrl.value.trim()
        if (url.isBlank()) {
            _ollamaStatus.value = OllamaStatus.Error("La URL no puede estar vacía")
            return
        }
        _ollamaStatus.value = OllamaStatus.Connecting
        viewModelScope.launch {
            // TODO Fase 9: Implementar llamada HTTP real a $url/api/tags para verificar conexión
            // Por ahora placeholder — se marcará como activo en la siguiente fase
            _ollamaStatus.value = OllamaStatus.Error("Conexión HTTP pendiente de implementación (Fase 9)")
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    init {
        viewModelScope.launch {
            repository.observeActiveDownloads().collect { workInfos ->
                val newStates = mutableMapOf<String, DownloadState>()
                for (workInfo in workInfos) {
                    val workId = workInfo.id.toString()
                    val modelIdTag = workInfo.tags.find { it.startsWith(ModelDownloadContract.UNIQUE_PREFIX) }
                    val modelId = modelIdTag?.removePrefix(ModelDownloadContract.UNIQUE_PREFIX) ?: continue

                    val state = when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt(ModelDownloadContract.PROGRESS_PERCENT, -1).takeIf { it >= 0 }
                            val bytes = workInfo.progress.getLong(ModelDownloadContract.PROGRESS_BYTES, 0L)
                            val total = workInfo.progress.getLong(ModelDownloadContract.PROGRESS_TOTAL, -1L).takeIf { it > 0 }
                            DownloadState.InProgress(workId, progress, bytes, total)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val path = workInfo.outputData.getString(ModelDownloadContract.OUTPUT_FILE_PATH) ?: ""
                            val mId = workInfo.outputData.getString(ModelDownloadContract.OUTPUT_MODEL_ID) ?: modelId
                            DownloadState.Completed(workId, mId, path, -1L)
                        }
                        WorkInfo.State.FAILED -> DownloadState.Failed(workId, "Error de descarga", false)
                        WorkInfo.State.ENQUEUED -> DownloadState.Enqueued(workId)
                        else -> DownloadState.NotStarted
                    }
                    newStates[modelId] = state
                }
                _downloadStates.value = newStates
            }
        }
    }

    fun startDownload(spec: ModelDownloadSpec) {
        viewModelScope.launch {
            repository.startModelDownload(spec).collect { state ->
                _downloadStates.update { it.toMutableMap().apply { put(spec.modelId, state) } }
            }
        }
    }

    fun cancelDownload(modelId: String) {
        repository.cancelDownload(modelId)
        _downloadStates.update { it.toMutableMap().apply { put(modelId, DownloadState.Cancelled(modelId, true)) } }
    }

    fun loadModel(modelId: String, path: String) {
        if (_isLoadingModel.value != null) return
        _isLoadingModel.value = modelId
        _errorMessage.value = null
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    llmEngine.loadModel(path)
                }.getOrElse { false }
            }
            if (success) {
                _activeModelId.value = modelId
            } else {
                _errorMessage.value = llmEngine.lastError.value ?: "Error: Memoria insuficiente para cargar el modelo"
            }
            _isLoadingModel.value = null
        }
    }

    class Factory(
        private val repository: DownloadRepository,
        private val llmEngine: LlmEngine
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CatalogViewModel(repository, llmEngine) as T
        }
    }
}

