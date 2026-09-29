package com.silf.app.domain.catalog

import com.silf.app.domain.download.ModelDownloadSpec
import com.silf.app.domain.download.ModelFormat

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
            id = "qwen2.5-1.5b-instruct-gguf",
            displayName = "Qwen 2.5 (1.5B) Instruct",
            description = "Formato GGUF · Modelo ultraligero y rápido. Ideal para dispositivos con 4 GB de RAM.",
            sizeLabel = "1.12 GB",
            requiresLicense = false,
            downloadSpec = ModelDownloadSpec(
                modelId = "qwen2.5-1.5b-instruct-gguf",
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                fileName = "qwen2_5_1_5b_instruct.gguf",
                format = ModelFormat.GGUF,
                maxBytes = 2L * 1024 * 1024 * 1024
            )
        ),
        ModelEntry(
            id = "qwen2.5-3b-instruct-gguf",
            displayName = "Qwen 2.5 (3B) Instruct",
            description = "Formato GGUF · Balance óptimo entre inteligencia y RAM. Recomendado para la mayoría de dispositivos.",
            sizeLabel = "2.14 GB",
            requiresLicense = false,
            downloadSpec = ModelDownloadSpec(
                modelId = "qwen2.5-3b-instruct-gguf",
                url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                fileName = "qwen2_5_3b_instruct.gguf",
                format = ModelFormat.GGUF,
                maxBytes = 3L * 1024 * 1024 * 1024
            )
        ),
        ModelEntry(
            id = "qwen2.5-7b-instruct-gguf",
            displayName = "Qwen 2.5 (7B) Instruct",
            description = "Formato GGUF · Máxima inteligencia local. Requiere dispositivo con ≥8 GB de RAM. Opción más avanzada.",
            sizeLabel = "4.68 GB",
            requiresLicense = false,
            downloadSpec = ModelDownloadSpec(
                modelId = "qwen2.5-7b-instruct-gguf",
                url = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
                fileName = "qwen2_5_7b_instruct.gguf",
                format = ModelFormat.GGUF,
                maxBytes = 6L * 1024 * 1024 * 1024
            )
        )
    )
}

