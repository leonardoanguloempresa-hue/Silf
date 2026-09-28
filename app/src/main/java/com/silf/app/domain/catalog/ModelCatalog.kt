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
            description = "Formato GGUF · Carga en Fase 5. Modelo ultraligero y rápido para respuestas directas.",
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
            description = "Formato GGUF · Carga en Fase 5. Excelente balance entre inteligencia y uso de RAM en móviles.",
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
            id = "llama-3.2-3b-instruct-gguf",
            displayName = "Llama 3.2 (3B) Instruct",
            description = "Formato GGUF · Carga en Fase 5. Modelo avanzado de Meta. Requiere aceptar licencia en HF.",
            sizeLabel = "2.18 GB",
            requiresLicense = true, // Mantiene el botón deshabilitado
            downloadSpec = ModelDownloadSpec(
                modelId = "llama-3.2-3b-instruct-gguf",
                url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                fileName = "llama3_2_3b_instruct.gguf",
                format = ModelFormat.GGUF,
                maxBytes = 3L * 1024 * 1024 * 1024
            )
        )
    )
}
