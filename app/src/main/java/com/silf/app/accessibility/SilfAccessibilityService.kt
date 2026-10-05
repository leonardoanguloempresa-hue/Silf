package com.silf.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Servicio de Accesibilidad para lectura de pantalla y ejecución física de acciones (clics por coordenadas).
 *
 * Recorre recursivamente las ventanas activas ignorando la propia aplicación (Silf)
 * y genera un snapshot simplificado de la UI inyectable como contexto al LLM.
 * Para ejecutar clics, obtiene las coordenadas reales en pantalla del nodo
 * y despacha un tap físico exacto con GestureDescription (API 24+), evitando
 * las fallas del performAction tradicional en MIUI y launchers personalizados.
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
     * Localiza la raíz de la ventana activa visible que no pertenezca a Silf
     * (útil cuando AssistantActivity se muestra como overlay flotante sobre otra app).
     */
    private fun findTargetWindowRoot(): AccessibilityNodeInfo? {
        try {
            val windowList = windows
            for (w in windowList) {
                val winRoot = w.root ?: continue
                if (winRoot.packageName != packageName) {
                    return winRoot
                } else {
                    recycleCompat(winRoot)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error buscando ventana no-Silf: ${e.message}")
        }
        return null
    }

    /**
     * Lee la ventana activa y devuelve su representación simplificada,
     * almacenando en savedNodes los nodos correspondientes a cada índice.
     * FILTRA e IGNORA la propia interfaz de Silf para evitar confusiones al LLM.
     */
    fun captureScreen(): String? {
        var root = rootInActiveWindow
        if (root == null || root.packageName == packageName) {
            recycleCompat(root)
            root = findTargetWindowRoot()
        }

        if (root == null || root.packageName == packageName) {
            recycleCompat(root)
            return null
        }

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
     * Recorrido recursivo en profundidad.
     * IGNORA/FILTRA todos los nodos cuyo packageName sea el de la propia aplicación (Silf).
     */
    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: StringBuilder,
        counter: IntArray
    ) {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return
        // Ignorar la propia UI de Silf
        if (node.packageName == packageName) return
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

            // Guardar copia del nodo para permitir clics futuros por coordenadas
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
     * Despacha un toque físico (tap) en las coordenadas exactas de la pantalla
     * usando la API GestureDescription (API 24+).
     * Esto funciona de forma garantizada en MIUI, ColorOS y lanzadores personalizados
     * donde node.performAction(ACTION_CLICK) suele fallar o ser bloqueado.
     */
    /**
     * Despacha un toque físico (tap) en las coordenadas exactas de la pantalla
     * usando la API GestureDescription (API 24+).
     * Envuelta en un try-catch protector para evitar cierres inesperados.
     */
    fun clickAt(x: Float, y: Float): Boolean {
        Log.i(TAG, "Despachando tap por GestureDescription en coordenadas: ($x, $y)")
        return try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "Gesto de clic en ($x, $y) completado exitosamente")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesto de clic en ($x, $y) cancelado por el sistema")
                }
            }, null)
            Log.i(TAG, "dispatchGesture retornado: $dispatched")
            dispatched
        } catch (e: Exception) {
            Log.e(TAG, "Error despachando gesto en ($x, $y): ${e.message}", e)
            false
        }
    }

    /**
     * Realiza un clic sobre el nodo especificado por su índice en la pantalla.
     * En lugar de performAction(), obtiene las coordenadas físicas del nodo:
     * val rect = Rect()
     * node.getBoundsInScreen(rect)
     * y usa GestureDescription para despachar un tap físico exacto en el centro
     * (rect.exactCenterX(), rect.exactCenterY()) tras verificar rect.width() > 0 && rect.height() > 0.
     */
    fun performClickOnNode(index: Int): Boolean {
        Log.i(TAG, "Solicitado clic por coordenadas en nodo con índice: $index")

        // 1. Buscar en la lista guardada de nodos
        val savedNode = savedNodes[index]
        if (savedNode != null) {
            try {
                val rect = Rect()
                savedNode.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val centerX = rect.exactCenterX()
                    val centerY = rect.exactCenterY()
                    Log.i(TAG, "Coordenadas obtenidas de savedNodes[$index]: bounds=$rect centro=($centerX, $centerY)")
                    val dispatched = clickAt(centerX, centerY)
                    if (dispatched) return true
                } else {
                    Log.w(TAG, "Nodo guardado [$index] tiene dimensiones inválidas: width=${rect.width()}, height=${rect.height()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al obtener coordenadas de nodo guardado [$index]: ${e.message}")
            }
        }

        // 2. Si no estaba en cache o bounds vacíos, buscar de forma fresca en el árbol activo
        var root = rootInActiveWindow
        if (root == null || root.packageName == packageName) {
            recycleCompat(root)
            root = findTargetWindowRoot()
        }

        if (root != null) {
            try {
                val freshTarget = findNodeByIndex(root, index)
                if (freshTarget != null) {
                    try {
                        val rect = Rect()
                        freshTarget.getBoundsInScreen(rect)
                        if (rect.width() > 0 && rect.height() > 0) {
                            val centerX = rect.exactCenterX()
                            val centerY = rect.exactCenterY()
                            Log.i(TAG, "Coordenadas de nodo fresco [$index]: bounds=$rect centro=($centerX, $centerY)")
                            return clickAt(centerX, centerY)
                        } else {
                            Log.w(TAG, "Nodo fresco [$index] tiene dimensiones inválidas: width=${rect.width()}, height=${rect.height()}")
                        }
                    } finally {
                        recycleCompat(freshTarget)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al buscar coordenadas en árbol activo [$index]: ${e.message}")
            } finally {
                recycleCompat(root)
            }
        }

        Log.e(TAG, "No se encontraron coordenadas válidas para el índice [$index]")
        return false
    }

    /**
     * Realiza un clic buscando el primer nodo con el texto especificado por coordenadas.
     */
    fun performClickOnNode(nodeText: String): Boolean {
        Log.i(TAG, "Solicitado clic en nodo con texto: \"$nodeText\"")
        var root = rootInActiveWindow
        if (root == null || root.packageName == packageName) {
            recycleCompat(root)
            root = findTargetWindowRoot()
        }
        if (root == null) return false
        try {
            val matchingNodes = root.findAccessibilityNodeInfosByText(nodeText)
            for (node in matchingNodes) {
                try {
                    if (node.packageName == packageName) continue
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        return clickAt(rect.exactCenterX(), rect.exactCenterY())
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
        if (node.packageName == packageName) return null
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
    private fun recycleCompat(node: AccessibilityNodeInfo?) {
        if (node == null) return
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
