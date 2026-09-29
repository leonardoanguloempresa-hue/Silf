package com.silf.app.data.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.silf.app.domain.download.ModelDownloadSpec
import com.silf.app.domain.download.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    private val notificationId: Int
        get() = ModelDownloadContract.NOTIFICATION_ID_BASE + id.hashCode().ushr(1)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo("Preparando descarga...", 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val spec = readSpecFromInput() ?: return@withContext fail("Especificación inválida")
        val validationError = spec.validate()
        if (validationError != null) return@withContext fail(validationError)

        try {
            setForeground(buildForegroundInfo(spec.fileName, 0))

            val modelsDir = File(applicationContext.filesDir, "models")
            if (!modelsDir.exists()) modelsDir.mkdirs()
            val outputFile = File(modelsDir, spec.fileName)

            var downloadedBytes = if (outputFile.exists()) outputFile.length() else 0L

            val requestBuilder = Request.Builder().url(spec.url)
            if (downloadedBytes > 0) {
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
            }

            val request = requestBuilder.build()
            activeCall = client.newCall(request)
            val response: Response = activeCall!!.execute()

            if (!response.isSuccessful) {
                if (response.code == 416) { // Range Not Satisfiable (probablemente ya descargado)
                    if (validateFile(outputFile, spec)) {
                        return@withContext success(outputFile, spec)
                    } else {
                        outputFile.delete()
                        return@withContext fail("Archivo corrupto, reintente descarga")
                    }
                }
                if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                    return@withContext fail("Error HTTP ${response.code}: ${response.message.ifBlank { "Error del servidor" }}")
                }
                if (runAttemptCount >= 3) {
                    return@withContext fail("Error HTTP ${response.code}: ${response.message.ifBlank { "Reintentos agotados" }}")
                }
                return@withContext Result.retry()
            }

            // Manejo de servidores que no soportan HTTP Range
            if (response.code == 200 && downloadedBytes > 0) {
                downloadedBytes = 0L
                outputFile.delete()
            }

            val body = response.body ?: return@withContext fail("Respuesta vacía del servidor")
            val totalBytes = downloadedBytes + body.contentLength()

            // Check space
            if (totalBytes > spec.maxBytes || applicationContext.filesDir.usableSpace < (totalBytes - downloadedBytes)) {
                return@withContext fail("Espacio insuficiente en disco o excede el límite máximo")
            }

            val randomAccessFile = RandomAccessFile(outputFile, "rw")
            randomAccessFile.seek(downloadedBytes)

            val inputStream = body.byteStream()
            val buffer = ByteArray(8 * 1024)
            var read: Int
            var lastReportedProgress = -1

            while (inputStream.read(buffer).also { read = it } != -1) {
                if (isStopped) {
                    activeCall?.cancel()
                    randomAccessFile.close()
                    return@withContext Result.retry()
                }

                randomAccessFile.write(buffer, 0, read)
                downloadedBytes += read

                if (totalBytes > 0) {
                    val progressPercent = ((downloadedBytes * 100) / totalBytes).toInt()
                    if (progressPercent >= lastReportedProgress + ModelDownloadContract.PROGRESS_STEP) {
                        lastReportedProgress = progressPercent
                        updateProgress(progressPercent, downloadedBytes, totalBytes, spec.fileName)
                    }
                }
            }

            randomAccessFile.close()

            if (validateFile(outputFile, spec)) {
                return@withContext success(outputFile, spec)
            } else {
                outputFile.delete()
                return@withContext fail("Validación de archivo (Tamaño/SHA) fallida")
            }

        } catch (e: IOException) {
            if (runAttemptCount >= 3) {
                return@withContext fail("Error de conexión: ${e.localizedMessage ?: "Fallo de red"}")
            }
            return@withContext Result.retry()
        } catch (e: Exception) {
            return@withContext fail("Error inesperado: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            activeCall?.cancel()
            activeCall = null
        }
    }

    private fun readSpecFromInput(): ModelDownloadSpec? {
        val id = inputData.getString(ModelDownloadContract.KEY_MODEL_ID) ?: return null
        val url = inputData.getString(ModelDownloadContract.KEY_MODEL_URL) ?: return null
        val name = inputData.getString(ModelDownloadContract.KEY_MODEL_NAME) ?: return null
        val expected = inputData.getLong(ModelDownloadContract.KEY_EXPECTED_BYTES, -1L).takeIf { it != -1L }
        val sha256 = inputData.getString(ModelDownloadContract.KEY_SHA256)
        val max = inputData.getLong(ModelDownloadContract.KEY_MAX_BYTES, 10L * 1024 * 1024 * 1024)

        val formatStr = inputData.getString(ModelDownloadContract.KEY_FORMAT)
        val format = if (formatStr != null) {
            try { ModelFormat.valueOf(formatStr) } catch(e: Exception) { ModelFormat.GGUF }
        } else {
            ModelFormat.GGUF
        }

        return ModelDownloadSpec(id, url, name, format, expected, sha256, max)
    }

    private fun validateFile(file: File, spec: ModelDownloadSpec): Boolean {
        if (!file.exists()) return false
        if (spec.expectedBytes != null && file.length() != spec.expectedBytes) return false
        if (spec.sha256 != null) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hash.equals(spec.sha256, ignoreCase = true)) return false
            } catch (e: Exception) {
                return false
            }
        }
        return true
    }

    private suspend fun updateProgress(percent: Int, bytes: Long, total: Long, name: String) {
        setProgress(
            workDataOf(
                ModelDownloadContract.PROGRESS_PERCENT to percent,
                ModelDownloadContract.PROGRESS_BYTES to bytes,
                ModelDownloadContract.PROGRESS_TOTAL to total
            )
        )
        setForeground(buildForegroundInfo(name, percent))
    }

    private fun buildForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ModelDownloadContract.CHANNEL_ID,
                "Descargas de Modelos",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, ModelDownloadContract.CHANNEL_ID)
            .setContentTitle("Descargando: $title")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun fail(reason: String): Result {
        return Result.failure(workDataOf(ModelDownloadContract.OUTPUT_ERROR to reason))
    }

    private fun success(file: File, spec: ModelDownloadSpec): Result {
        return Result.success(
            workDataOf(
                ModelDownloadContract.OUTPUT_FILE_PATH to file.absolutePath,
                ModelDownloadContract.OUTPUT_MODEL_ID to spec.modelId
            )
        )
    }
}
