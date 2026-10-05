package com.silf.app.ui.screens.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.silf.app.data.workers.ModelDownloadContract
import com.silf.app.domain.catalog.ModelCatalog
import com.silf.app.domain.catalog.ModelEntry
import com.silf.app.domain.download.DownloadRepository
import com.silf.app.domain.download.DownloadState
import com.silf.app.domain.download.ModelDownloadSpec
import com.silf.app.domain.llm.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun dismissError() {
        _errorMessage.value = null
    }

    init {
        // Inicializar estado verificando si los modelos ya existen en almacenamiento local
        val initialMap = mutableMapOf<String, DownloadState>()
        for (entry in availableModels) {
            val file = repository.getDownloadedModelFile(entry.downloadSpec.fileName)
            if (file.exists() && file.length() > 0) {
                initialMap[entry.id] = DownloadState.Completed(
                    workId = "",
                    modelId = entry.id,
                    filePath = file.absolutePath,
                    bytes = file.length()
                )
            }
        }
        _downloadStates.value = initialMap

        // Observar descargas activas en segundo plano con WorkManager
        viewModelScope.launch {
            repository.observeActiveDownloads().collect { workInfos ->
                val newStates = _downloadStates.value.toMutableMap()
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
                        WorkInfo.State.FAILED -> {
                            val error = workInfo.outputData.getString(ModelDownloadContract.OUTPUT_ERROR) ?: "Error de descarga"
                            _errorMessage.value = "Error de descarga: $error"
                            DownloadState.Failed(workId, error, false)
                        }
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
            try {
                repository.startModelDownload(spec).collect { state ->
                    _downloadStates.update { it.toMutableMap().apply { put(spec.modelId, state) } }
                    if (state is DownloadState.Failed) {
                        _errorMessage.value = "Error de descarga: ${state.reason}"
                    }
                }
            } catch (e: Exception) {
                val reason = e.localizedMessage ?: "Fallo al iniciar descarga"
                _downloadStates.update {
                    it.toMutableMap().apply {
                        put(spec.modelId, DownloadState.Failed(null, reason, false))
                    }
                }
                _errorMessage.value = "Error de descarga: $reason"
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
