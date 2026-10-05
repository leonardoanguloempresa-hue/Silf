package com.silf.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        val eventPkg = event.packageName?.toString().orEmpty()
        // Cuando el evento provenga del paquete de Silf (com.silf.app o packageName),
        // NO borres ni actualices la lista actual de nodos (savedNodes) ni el screenSnapshot.
        // Debes conservar intacta la última "foto" de la pantalla que el usuario estaba viendo antes de invocar a Silf.
        if (eventPkg == packageName || eventPkg == "com.silf.app") {
            Log.d(TAG, "Evento ignorado de Silf ($eventPkg): conservando snapshot congelado (${savedNodes.size} nodos)")
            return
        }

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
                val pkg = winRoot.packageName?.toString().orEmpty()
                if (pkg != packageName && pkg != "com.silf.app") {
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
     * Si la ventana activa es de Silf y no se encuentra otra ventana, conserva
     * intactos savedNodes y el snapshot congelado.
     */
    fun captureScreen(): String? {
        var root = rootInActiveWindow
        val isSilfActive = root == null || root.packageName == packageName || root.packageName?.toString() == "com.silf.app"

        if (isSilfActive) {
            recycleCompat(root)
            root = findTargetWindowRoot()
        }

        // Si la ventana encontrada sigue siendo de Silf o no existe,
        // NO borramos savedNodes ni _screenSnapshot para preservar intacta la última foto congelada.
        if (root == null || root.packageName == packageName || root.packageName?.toString() == "com.silf.app") {
            recycleCompat(root)
            Log.d(TAG, "Ventana externa no-Silf no disponible. Conservando snapshot y nodos congelados (${savedNodes.size} nodos).")
            return _screenSnapshot.value.takeIf { it.isNotBlank() }
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
        val result = sb.toString().trimEnd()
        _screenSnapshot.value = result
        Log.i(TAG, "Snapshot congelado actualizado para ${root.packageName}: ${counter[0]} nodos indexados")
        return result
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
     * Envuelta en un try-catch protector y garantizada para ejecutarse en el Hilo Principal
     * (Handler(Looper.getMainLooper()).post) ya que MIUI / Android descarta gestos de hilos secundarios.
     */
    fun clickAt(x: Float, y: Float): Boolean {
        Log.i(TAG, "Despachando tap por GestureDescription en coordenadas: ($x, $y)")
        return try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            if (Looper.myLooper() == Looper.getMainLooper()) {
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "Gesto de clic en ($x, $y) completado exitosamente")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesto de clic en ($x, $y) cancelado por el sistema")
                    }
                }, null)
                Log.i(TAG, "dispatchGesture retornado en Main thread: $dispatched")
                dispatched
            } else {
                var dispatched = false
                Handler(Looper.getMainLooper()).post {
                    try {
                        dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                Log.i(TAG, "Gesto de clic en ($x, $y) completado exitosamente")
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                Log.w(TAG, "Gesto de clic en ($x, $y) cancelado por el sistema")
                            }
                        }, null)
                        Log.i(TAG, "dispatchGesture retornado vía Handler(Looper.getMainLooper()): $dispatched")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en dispatchGesture desde Handler: ${e.message}", e)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error despachando gesto en ($x, $y): ${e.message}", e)
            false
        }
    }

    /**
     * Si el nodo no tiene isClickable == true, busca recursivamente hacia arriba:
     * var current = node; while(current != null && !current.isClickable) { current = current.parent }
     * Si encuentra un parent clickeable, usa ese.
     */
    private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null && !current.isClickable) {
            val next = current.parent
            recycleCompat(current)
            current = next
        }
        if (current != null && current.isClickable) {
            Log.i(TAG, "Nodo original no clickeable. Encontrado parent clickeable: ${current.className}")
            return current
        }
        return node
    }

    /**
     * Realiza un clic sobre el nodo especificado por su índice en la pantalla.
     * Si el nodo no es clickeable, busca recursivamente un parent clickeable.
     * Retorna el Rect con las coordenadas reales del clic, o null si falla.
     */
    fun performClickOnNode(index: Int): Rect? {
        Log.i(TAG, "Solicitado clic por coordenadas en nodo con índice: $index")

        // 1. Buscar en la lista guardada de nodos
        val savedNode = savedNodes[index]
        if (savedNode != null) {
            try {
                val target = findClickableTarget(savedNode)
                val rect = Rect()
                target.getBoundsInScreen(rect)
                if (target !== savedNode) {
                    recycleCompat(target)
                }
                if (rect.width() > 0 && rect.height() > 0) {
                    val centerX = rect.exactCenterX()
                    val centerY = rect.exactCenterY()
                    Log.i(TAG, "Coordenadas obtenidas de savedNodes[$index]: bounds=$rect centro=($centerX, $centerY)")
                    val dispatched = clickAt(centerX, centerY)
                    if (dispatched) return rect
                } else {
                    Log.w(TAG, "Nodo guardado [$index] tiene dimensiones inválidas: width=${rect.width()}, height=${rect.height()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallo al obtener coordenadas de nodo guardado [$index]: ${e.message}")
            }
        }

        // 2. Si no estaba en la lista congelada o bounds vacíos, buscar de forma fresca en el árbol activo
        var root = rootInActiveWindow
        if (root == null || root.packageName == packageName || root.packageName?.toString() == "com.silf.app") {
            recycleCompat(root)
            root = findTargetWindowRoot()
        }

        if (root != null) {
            try {
                val freshTarget = findNodeByIndex(root, index)
                if (freshTarget != null) {
                    try {
                        val target = findClickableTarget(freshTarget)
                        val rect = Rect()
                        target.getBoundsInScreen(rect)
                        if (target !== freshTarget) {
                            recycleCompat(target)
                        }
                        if (rect.width() > 0 && rect.height() > 0) {
                            val centerX = rect.exactCenterX()
                            val centerY = rect.exactCenterY()
                            Log.i(TAG, "Coordenadas de nodo fresco [$index]: bounds=$rect centro=($centerX, $centerY)")
                            val dispatched = clickAt(centerX, centerY)
                            if (dispatched) return rect
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
        return null
    }

    /**
     * Realiza un clic buscando el primer nodo con el texto especificado por coordenadas.
     */
    fun performClickOnNode(nodeText: String): Rect? {
        Log.i(TAG, "Solicitado clic en nodo con texto: \"$nodeText\"")
        var root = rootInActiveWindow
        if (root == null || root.packageName == packageName || root.packageName?.toString() == "com.silf.app") {
            recycleCompat(root)
            root = findTargetWindowRoot()
        }
        if (root == null) return null
        try {
            val matchingNodes = root.findAccessibilityNodeInfosByText(nodeText)
            for (node in matchingNodes) {
                try {
                    val pkg = node.packageName?.toString().orEmpty()
                    if (pkg == packageName || pkg == "com.silf.app") continue
                    val target = findClickableTarget(node)
                    val rect = Rect()
                    target.getBoundsInScreen(rect)
                    if (target !== node) {
                        recycleCompat(target)
                    }
                    if (rect.width() > 0 && rect.height() > 0) {
                        val dispatched = clickAt(rect.exactCenterX(), rect.exactCenterY())
                        if (dispatched) return rect
                    }
                } finally {
                    recycleCompat(node)
                }
            }
        } finally {
            recycleCompat(root)
        }
        Log.e(TAG, "No se encontró ningún nodo clickable con texto \"$nodeText\"")
        return null
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
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg == "com.silf.app") return null
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
            return instance?.performClickOnNode(nodeIndex) != null
        }

        /**
         * Permite ejecutar un clic por texto desde cualquier ViewModel o componente.
         */
        fun performClick(nodeText: String): Boolean {
            return instance?.performClickOnNode(nodeText) != null
        }

        /**
         * Obtiene el snapshot congelado de la pantalla activa para el prompt.
         * Si ya existe un snapshot previo y nodos guardados, se conservan intactos
         * para no borrar la pantalla que el usuario estaba viendo antes de abrir Silf.
         */
        fun getFreshScreenSnapshot(): String {
            val inst = instance ?: return _screenSnapshot.value
            if (_screenSnapshot.value.isNotBlank() && inst.savedNodes.isNotEmpty()) {
                Log.d(TAG, "getFreshScreenSnapshot: usando snapshot congelado con ${inst.savedNodes.size} nodos")
                return _screenSnapshot.value
            }
            return try {
                val fresh = inst.captureScreen()
                if (fresh != null) {
                    _screenSnapshot.value = fresh
                    fresh
                } else {
                    _screenSnapshot.value
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error refrescando snapshot en tiempo real: ${e.message}")
                _screenSnapshot.value
            }
        }
    }
}
