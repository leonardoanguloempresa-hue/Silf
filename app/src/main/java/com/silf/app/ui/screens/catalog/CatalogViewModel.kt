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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CatalogViewModel(
    private val repository: DownloadRepository
) : ViewModel() {

    val availableModels: List<ModelEntry> = ModelCatalog.models
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

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
                        WorkInfo.State.SUCCEEDED -> DownloadState.Completed(workId, modelId, "", 0L)
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

    class Factory(private val repository: DownloadRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CatalogViewModel(repository) as T
        }
    }
}
