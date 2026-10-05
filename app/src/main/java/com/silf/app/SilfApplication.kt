package com.silf.app

import android.app.Application
import com.silf.app.di.AppContainer
import com.silf.app.domain.llm.LlamaCppEngine
import com.silf.app.domain.llm.LlmEngine

/**
 * SilfApp / SilfApplication:
 * Mantiene el ciclo de vida global de la aplicación, el contenedor de dependencias
 * y asegura la inicialización y persistencia del modelo LLM en RAM.
 */
typealias SilfApp = SilfApplication

class SilfApplication : Application() {
    val container by lazy { AppContainer(this) }

    val llmEngine: LlmEngine
        get() = LlamaCppEngine.getInstance(this)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Inicializar el Singleton de LlamaCppEngine e intentar auto-cargar el modelo si existe ruta previa
        LlamaCppEngine.init(this)
    }

    companion object {
        lateinit var instance: SilfApplication
            private set
    }
}
