package com.silf.app.domain.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.silf.app.data.workers.ModelDownloadContract
import com.silf.app.data.workers.ModelDownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadRepository(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun startModelDownload(spec: ModelDownloadSpec): Flow<DownloadState> {
        val validation = spec.validate()
        require(validation == null) { validation ?: "Error de validación" }

        val inputDataBuilder = Data.Builder()
            .putString(ModelDownloadContract.KEY_MODEL_ID, spec.modelId)
            .putString(ModelDownloadContract.KEY_MODEL_URL, spec.url)
            .putString(ModelDownloadContract.KEY_MODEL_NAME, spec.fileName)
            .putLong(ModelDownloadContract.KEY_MAX_BYTES, spec.maxBytes)

        spec.expectedBytes?.let { inputDataBuilder.putLong(ModelDownloadContract.KEY_EXPECTED_BYTES, it) }
        spec.sha256?.let { inputDataBuilder.putString(ModelDownloadContract.KEY_SHA256, it) }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uniqueWorkName = ModelDownloadContract.UNIQUE_PREFIX + spec.modelId

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputDataBuilder.build())
            .setConstraints(constraints)
            .build()

        // Evitar descargas duplicadas del mismo modelo
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            request
        )

        return observeDownload(request.id.toString(), spec.modelId)
    }

    private fun observeDownload(workIdString: String, modelId: String): Flow<DownloadState> {
        return workManager.getWorkInfoByIdFlow(java.util.UUID.fromString(workIdString)).map { workInfo ->
            if (workInfo == null) return@map DownloadState.Failed(workIdString, "WorkInfo nulo", false)

            when (workInfo.state) {
                WorkInfo.State.ENQUEUED -> DownloadState.Enqueued(workIdString)
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getInt(ModelDownloadContract.PROGRESS_PERCENT, -1).takeIf { it >= 0 }
                    val bytes = workInfo.progress.getLong(ModelDownloadContract.PROGRESS_BYTES, 0L)
                    val total = workInfo.progress.getLong(ModelDownloadContract.PROGRESS_TOTAL, -1L).takeIf { it > 0 }
                    DownloadState.InProgress(workIdString, progress, bytes, total)
                }
                WorkInfo.State.SUCCEEDED -> {
                    val path = workInfo.outputData.getString(ModelDownloadContract.OUTPUT_FILE_PATH) ?: ""
                    val mId = workInfo.outputData.getString(ModelDownloadContract.OUTPUT_MODEL_ID) ?: modelId
                    DownloadState.Completed(workIdString, mId, path, -1L)
                }
                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString(ModelDownloadContract.OUTPUT_ERROR) ?: "Error desconocido"
                    DownloadState.Failed(workIdString, error, false)
                }
                WorkInfo.State.CANCELLED -> DownloadState.Cancelled(workIdString, true)
                WorkInfo.State.BLOCKED -> DownloadState.Preparing(workIdString)
            }
        }
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork(ModelDownloadContract.UNIQUE_PREFIX + modelId)
    }
}
