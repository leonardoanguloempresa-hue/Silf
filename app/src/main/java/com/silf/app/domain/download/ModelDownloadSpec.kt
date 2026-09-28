package com.silf.app.domain.download

data class ModelDownloadSpec(
    val modelId: String,
    val url: String,
    val fileName: String,
    val expectedBytes: Long? = null,
    val sha256: String? = null,
    val maxBytes: Long = 5L * 1024L * 1024L * 1024L // Límite de 5GB por defecto
) {
    fun validate(): String? {
        if (modelId.isBlank()) return "Model ID no puede estar vacío"
        if (!url.startsWith("https://")) return "La URL debe ser HTTPS"
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) return "Nombre de archivo inválido (prevención de path traversal)"
        if (!fileName.endsWith(".task") && !fileName.endsWith(".litertlm")) return "Extensión no permitida. Use .task o .litertlm"
        if (expectedBytes != null && (expectedBytes <= 0 || expectedBytes > maxBytes)) return "Tamaño esperado fuera de los límites permitidos"
        if (sha256 != null && !sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) return "Formato SHA-256 inválido"
        return null
    }
}
