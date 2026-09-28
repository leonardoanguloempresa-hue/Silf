package com.silf.app.domain.download

enum class ModelFormat { TASK, LITERTLM, GGUF }

data class ModelDownloadSpec(
    val modelId: String,
    val url: String,
    val fileName: String,
    val format: ModelFormat,
    val expectedBytes: Long? = null,
    val sha256: String? = null,
    val maxBytes: Long = 5L * 1024L * 1024L * 1024L
) {
    companion object {
        private val ALLOWED_EXTENSIONS = setOf(".task", ".litertlm", ".gguf")
        private val MULTIPART_REGEX = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)
    }

    fun validate(): String? {
        if (modelId.isBlank()) return "Model ID no puede estar vacío"
        if (!url.startsWith("https://")) return "La URL debe ser HTTPS"
        if (fileName.isBlank()) return "Nombre de archivo vacío"
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains(".."))
            return "Ruta no permitida en el nombre de archivo"

        val hasValidExtension = ALLOWED_EXTENSIONS.any { fileName.endsWith(it, ignoreCase = true) }
        if (!hasValidExtension) return "Extensión no permitida. Use .task, .litertlm o .gguf"

        if (MULTIPART_REGEX.containsMatchIn(fileName)) {
            return "Los modelos multipart no están soportados en esta fase. Descargue archivos GGUF individuales."
        }

        if (expectedBytes != null && (expectedBytes <= 0 || expectedBytes > maxBytes)) return "Tamaño esperado fuera de los límites"
        if (sha256 != null && !sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) return "Formato SHA-256 inválido"
        return null
    }
}
