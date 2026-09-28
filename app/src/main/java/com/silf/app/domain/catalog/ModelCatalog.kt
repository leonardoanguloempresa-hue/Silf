package com.silf.app.domain.catalog

import com.silf.app.domain.download.ModelDownloadSpec

data class ModelEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeLabel: String,
    val requiresLicense: Boolean,
    val downloadSpec: ModelDownloadSpec
)

object ModelCatalog {
    val models = listOf(
        ModelEntry(
            id = "gemma-2b-it",
            displayName = "Gemma 2B IT",
            description = "Modelo oficial de Google optimizado para CPU. Requiere aceptar licencia.",
            sizeLabel = "1.3 GB",
            requiresLicense = true, // Deshabilitará el botón
            downloadSpec = ModelDownloadSpec(
                modelId = "gemma-2b-it",
                url = "https://huggingface.co/google/gemma-2b-it-cpu/resolve/main/gemma-2b-it-cpu-int8.task",
                fileName = "gemma-2b-it.task",
                maxBytes = 2L * 1024 * 1024 * 1024
            )
        ),
        ModelEntry(
            id = "qwen-1.5b-chat",
            displayName = "Qwen 1.5B Chat",
            description = "Modelo rápido y eficiente para dispositivos móviles.",
            sizeLabel = "900 MB",
            requiresLicense = false,
            downloadSpec = ModelDownloadSpec(
                modelId = "qwen-1.5b-chat",
                url = "https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q4_0.gguf", // NOTA: MediaPipe prefiere .task, esto es ejemplo visual
                fileName = "qwen-1.5b-chat.task",
                maxBytes = 1L * 1024 * 1024 * 1024
            )
        )
    )
}
