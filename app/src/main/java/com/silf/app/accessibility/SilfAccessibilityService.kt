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
 * Fase 9 y 11: Servicio de Accesibilidad para lectura de pantalla y ejecución de acciones (clics).
 *
 * Recorre recursivamente rootInActiveWindow y genera un snapshot simplificado de la UI
 * inyectable como contexto al LLM. Además, almacena referencias a los nodos indexados
 * para permitir que el agente IA interactúe con la interfaz mediante [CLICK: X].
 */
class SilfAccessibilityService : AccessibilityService() {

    // Nodos guardados del último snapshot indexado
    private val savedNodes = mutableMapOf<Int, AccessibilityNodeInfo>()

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
        clearSavedNodes()
        super.onDestroy()
    }

    private fun clearSavedNodes() {
        for ((_, node) in savedNodes) {
            recycleCompat(node)
        }
        savedNodes.clear()
    }

    /**
     * Lee la ventana activa y devuelve su representación simplificada,
     * almacenando en savedNodes los nodos correspondientes a cada índice.
     */
    fun captureScreen(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null

        clearSavedNodes()

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
     * Recorrido recursivo en profundidad. Guarda nodos interactivos o informativos
     * asociándolos al índice actual.
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
            val idx = counter[0]
            val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
            out.append("[").append(idx).append("] ").append(className)
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

            // Guardar copia del nodo para permitir clics futuros por índice
            try {
                savedNodes[idx] = AccessibilityNodeInfo.obtain(node)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo obtener copia del nodo [$idx]: ${e.message}")
            }

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

    /**
     * Fase 11: Realiza un clic sobre el nodo especificado por su índice en la pantalla.
     * Busca primero en la lista guardada de nodos (savedNodes). Cuando encuentra el nodo,
     * ejecuta node.performAction(AccessibilityNodeInfo.ACTION_CLICK).
     * Si ese nodo específico no tiene isClickable == true, intenta ejecutar el clic
     * en su parent de forma recursiva hasta encontrar un contenedor clickeable.
     */
    fun performClickOnNode(index: Int): Boolean {
        Log.i(TAG, "Solicitado clic en nodo con índice: $index")

        // 1. Buscar en la lista guardada de nodos
        val savedNode = savedNodes[index]
        if (savedNode != null) {
            try {
                if (clickNodeOrParent(savedNode)) {
                    Log.i(TAG, "Clic exitoso en nodo guardado [$index]")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al interactuar con nodo guardado [$index]: ${e.message}")
            }
        }

        // 2. Si no estaba en cache o falló, buscar de forma fresca en el árbol activo
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val freshTarget = findNodeByIndex(root, index)
                if (freshTarget != null) {
                    try {
                        val success = clickNodeOrParent(freshTarget)
                        Log.i(TAG, "Clic en nodo fresco [$index]: $success")
                        return success
                    } finally {
                        recycleCompat(freshTarget)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallo buscando nodo fresco [$index]: ${e.message}")
            } finally {
                recycleCompat(root)
            }
        }

        Log.e(TAG, "No se encontró ningún nodo válido para el índice [$index]")
        return false
    }

    /**
     * Fase 11: Realiza un clic buscando el primer nodo con el texto especificado.
     */
    fun performClickOnNode(nodeText: String): Boolean {
        Log.i(TAG, "Solicitado clic en nodo con texto: \"$nodeText\"")
        val root = rootInActiveWindow ?: return false
        try {
            val matchingNodes = root.findAccessibilityNodeInfosByText(nodeText)
            for (node in matchingNodes) {
                try {
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "Clic exitoso en nodo con texto \"$nodeText\"")
                        return true
                    }
                } finally {
                    recycleCompat(node)
                }
            }
        } finally {
            recycleCompat(root)
        }
        Log.e(TAG, "No se encontró ningún nodo clickable con texto \"$nodeText\"")
        return false
    }

    /**
     * Ejecuta ACTION_CLICK en el nodo.
     * Si ese nodo específico no tiene isClickable == true, busca recursivamente
     * en su jerarquía de ancestros (parent) hasta encontrar un contenedor clickeable
     * y ejecuta el clic en él.
     */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        // 1. Si el nodo específico tiene isClickable == true, intentar el clic directo
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 2. Si no es clickeable o falló, buscar recursivamente en sus padres (parent)
        // hasta encontrar un contenedor clickeable
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            try {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "Clic exitoso en parent clickeable: ${current.className}")
                    recycleCompat(current)
                    return true
                }
                val next = current.parent
                recycleCompat(current)
                current = next
            } catch (e: Exception) {
                Log.w(TAG, "Error recorriendo jerarquía de padres: ${e.message}")
                break
            }
        }

        // 3. Como fallback de último recurso, intentar ACTION_CLICK directo en el nodo original
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeByIndex(root: AccessibilityNodeInfo, targetIndex: Int): AccessibilityNodeInfo? {
        val counter = intArrayOf(0)
        return searchByIndex(root, depth = 0, counter = counter, targetIndex = targetIndex)
    }

    private fun searchByIndex(
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
        targetIndex: Int
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || counter[0] > targetIndex) return null
        if (!node.isVisibleToUser) return null

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable

        if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
            if (counter[0] == targetIndex) {
                return AccessibilityNodeInfo.obtain(node)
            }
            counter[0]++
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = searchByIndex(child, depth + 1, counter, targetIndex)
                if (found != null) return found
            } finally {
                recycleCompat(child)
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try { node.recycle() } catch (_: IllegalStateException) { }
        }
    }

    companion object {
        private const val TAG = "SilfA11y"
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 300
        private const val MAX_TEXT_LEN = 120

        @Volatile
        var instance: SilfAccessibilityService? = null
            private set

        private val _screenSnapshot = MutableStateFlow("")
        val screenSnapshot: StateFlow<String> = _screenSnapshot.asStateFlow()

        val isEnabled: Boolean get() = instance != null

        /**
         * Permite ejecutar un clic por índice desde cualquier ViewModel o componente.
         */
        fun performClick(nodeIndex: Int): Boolean {
            return instance?.performClickOnNode(nodeIndex) ?: false
        }

        /**
         * Permite ejecutar un clic por texto desde cualquier ViewModel o componente.
         */
        fun performClick(nodeText: String): Boolean {
            return instance?.performClickOnNode(nodeText) ?: false
        }
    }
}
