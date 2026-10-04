package com.silf.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fase 9: Servicio de Accesibilidad (solo lectura de pantalla).
 *
 * Recorre recursivamente rootInActiveWindow y genera un "String simplificado" de la UI
 * pensado para inyectarse como contexto al LLM. Ejemplo de salida:
 *
 *   [app: com.android.settings]
 *   [0] TextView "Wi-Fi"
 *   [1] Switch "Wi-Fi" desc="Activado" (clickable,checked)
 *   [2] Button "Guardar" (clickable)
 *
 * Los índices [n] quedan reservados para que en una fase posterior el LLM pueda
 * referirse a un nodo concreto (p. ej. "click 2").
 */
class SilfAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Servicio de accesibilidad conectado")
        try {
            val snapshot = captureScreen()
            if (snapshot != null) _screenSnapshot.value = snapshot
        } catch (e: Exception) {
            Log.e(TAG, "Error capturando pantalla inicial", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // No sobreescribir el snapshot con la pantalla de la propia aplicación Silf
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                try {
                    val snapshot = captureScreen()
                    if (snapshot != null) _screenSnapshot.value = snapshot
                } catch (e: Exception) {
                    // Nunca tumbar el servicio por un árbol de UI inesperado
                    Log.e(TAG, "Error leyendo la pantalla", e)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Servicio de accesibilidad interrumpido")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _screenSnapshot.value = ""
        }
        super.onDestroy()
    }

    /**
     * Lee la ventana activa y devuelve su representación simplificada,
     * o null si no hay ventana disponible o si la ventana pertenece a Silf.
     */
    fun captureScreen(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null
        val sb = StringBuilder()
        sb.append("[app: ").append(root.packageName ?: "desconocida").append("]\n")
        val counter = intArrayOf(0)
        try {
            traverse(root, depth = 0, out = sb, counter = counter)
        } finally {
            recycleCompat(root)
        }
        return sb.toString().trimEnd()
    }

    /**
     * Recorrido recursivo en profundidad. Solo emite nodos con información útil
     * (texto, descripción o interactivos); los contenedores vacíos se omiten pero
     * sus hijos sí se visitan.
     */
    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: StringBuilder,
        counter: IntArray
    ) {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable

        if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
            val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
            out.append("[").append(counter[0]).append("] ").append(className)
            if (text.isNotEmpty()) out.append(" \"").append(text.take(MAX_TEXT_LEN)).append('"')
            if (desc.isNotEmpty() && desc != text) {
                out.append(" desc=\"").append(desc.take(MAX_TEXT_LEN)).append('"')
            }
            val flags = buildList {
                if (clickable) add("clickable")
                if (editable) add("editable")
                if (scrollable) add("scrollable")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
            }
            if (flags.isNotEmpty()) out.append(" (").append(flags.joinToString(",")).append(')')
            out.append('\n')
            counter[0]++
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverse(child, depth + 1, out, counter)
            } finally {
                recycleCompat(child)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(node: AccessibilityNodeInfo) {
        // En API 33+ recycle() es no-op; en versiones previas evita fugas del pool.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try { node.recycle() } catch (_: IllegalStateException) { }
        }
    }

    companion object {
        private const val TAG = "SilfA11y"
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 300
        private const val MAX_TEXT_LEN = 120

        /** Instancia activa (null si el usuario no ha habilitado el servicio). */
        @Volatile
        var instance: SilfAccessibilityService? = null
            private set

        private val _screenSnapshot = MutableStateFlow("")
        /** Última captura simplificada de la pantalla, observable desde UI/ViewModel. */
        val screenSnapshot: StateFlow<String> = _screenSnapshot.asStateFlow()

        val isEnabled: Boolean get() = instance != null
    }
}
