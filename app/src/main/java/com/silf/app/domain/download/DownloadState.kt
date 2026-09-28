package com.silf.app.domain.download

sealed interface DownloadState {
    data class Enqueued(val workId: String) : DownloadState
    data class Preparing(val workId: String) : DownloadState
    data class InProgress(
        val workId: String,
        val progress: Int?,
        val bytesDownloaded: Long,
        val totalBytes: Long?
    ) : DownloadState
    data class Paused(val workId: String, val bytesDownloaded: Long, val totalBytes: Long?) : DownloadState
    data class Completed(
        val workId: String,
        val modelId: String,
        val filePath: String,
        val bytes: Long
    ) : DownloadState
    data class Failed(val workId: String?, val reason: String, val recoverable: Boolean) : DownloadState
    data class Cancelled(val workId: String, val partialFileKept: Boolean) : DownloadState
}
