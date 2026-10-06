package com.silf.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
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

    // Diccionario de coordenadas para bypass de reciclaje de AccessibilityNodeInfo
    val coordinateMap = ConcurrentHashMap<Int, Rect>()

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

        // Si la ventana que cambió es la propia Silf (ej. AssistantActivity abriéndose)
        if (eventPkg == packageName || eventPkg == "com.silf.app") {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                showMemoryDebugToast()
            }
            Log.d(TAG, "Evento ignorado de Silf ($eventPkg): conservando coordinateMap congelado (${coordinateMap.size} coordenadas)")
            return
        }

        // Filtro de Actualización:
        // Asegúrate de que coordinateMap y screenSnapshot se actualicen SOLAMENTE cuando
        // el AccessibilityEvent provenga de un paquete distinto a tu propia app y no sea teclado/IME.
        if (eventPkg.isEmpty() || eventPkg.contains("inputmethod") || eventPkg.contains("keyboard")) {
            Log.d(TAG, "Evento ignorado ($eventPkg): conservando coordinateMap congelado (${coordinateMap.size} coordenadas)")
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
        coordinateMap.clear()
        super.onDestroy()
    }

    /**
     * Localiza la raíz de la ventana activa visible que no pertenezca a Silf
     * (útil cuando AssistantActivity se muestra como overlay flotante sobre otra app).
     */
    private fun findTargetWindowRoot(): AccessibilityNodeInfo? {
        try {
            val windowList = windows
            for (w in windowList) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val winRoot = w.root ?: continue
                val pkg = winRoot.packageName?.toString().orEmpty()
                if (pkg != packageName && pkg != "com.silf.app" && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) {
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
     * extrayendo y almacenando en coordinateMap los límites de pantalla de cada nodo.
     * FILTRA e IGNORA la propia interfaz de Silf.
     * Si la ventana activa es de Silf y no se encuentra otra ventana externa,
     * conserva intacto coordinateMap y el snapshot congelado.
     */
    fun captureScreen(): String? {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "Seguro Anti-Borrado: rootInActiveWindow es nulo. Conservando ${coordinateMap.size} nodos en memoria.")
            return _screenSnapshot.value.takeIf { it.isNotBlank() }
        }

        val rootPkg = root.packageName?.toString().orEmpty()

        // Seguro Anti-Borrado:
        // Si rootInActiveWindow?.packageName == "com.silf.app" (o el paquete de esta app),
        // ABORTA el escaneo (return). No limpies el mapa ni actualices el texto. Conserva los datos de la app de fondo.
        if (rootPkg.isEmpty() || rootPkg == packageName || rootPkg == "com.silf.app" ||
            rootPkg.contains("inputmethod") || rootPkg.contains("keyboard")) {
            recycleCompat(root)
            Log.d(TAG, "Seguro Anti-Borrado activado: ventana activa es $rootPkg. Abortando escaneo para conservar ${coordinateMap.size} nodos en memoria.")
            return _screenSnapshot.value.takeIf { it.isNotBlank() }
        }

        // Actualizar coordinateMap y snapshot SOLO con la app externa
        coordinateMap.clear()

        val sb = StringBuilder()
        sb.append("[app: ").append(rootPkg).append("]\n")
        val counter = intArrayOf(0)
        try {
            traverse(root, depth = 0, out = sb, counter = counter)
        } finally {
            recycleCompat(root)
        }
        val result = sb.toString().trimEnd()
        _screenSnapshot.value = result
        Log.i(TAG, "Snapshot y coordinateMap actualizados para $rootPkg: ${counter[0]} coordenadas indexadas (tamaño map: ${coordinateMap.size})")
        return result
    }

    /**
     * Recorrido recursivo en profundidad.
     * IGNORA/FILTRA todos los nodos cuyo packageName sea el de la propia aplicación (Silf).
     * Para cada nodo elegible, extrae los límites de pantalla (Rect) y los guarda en coordinateMap.
     */
    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: StringBuilder,
        counter: IntArray
    ) {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg == "com.silf.app") return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable

        if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
            val i = counter[0]

            // Población Síncrona:
            // Justo en el momento en que asignas un índice i a un nodo y lo añades al texto,
            // DEBES extraer sus coordenadas y guardarlas:
            val rect = Rect()
            node.getBoundsInScreen(rect)
            coordinateMap[i] = rect

            val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
            out.append("[").append(i).append("] ").append(className)
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
                Handler(Looper.getMainLooper()).post {
                    try {
                        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
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
     * Toques Ciegos (Blind Taps): Busca directamente en coordinateMap por ID.
     * Ya no interactúa con el objeto AccessibilityNodeInfo para evitar el reciclaje e invalidación del OS.
     * Si el Rect existe, calcula exactCenterX() y exactCenterY() y despacha GestureDescription en el Main Thread.
     * Retorna el Rect encontrado, o null si el ID no existe en coordinateMap.
     */
    fun performClickOnNode(id: Int): Rect? {
        val rect = coordinateMap[id]
        if (rect == null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Silf: Coordenadas no guardadas para ID $id", Toast.LENGTH_SHORT).show()
            }
            return null
        }

        val x = rect.exactCenterX()
        val y = rect.exactCenterY()

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply {
                        moveTo(x, y)
                        lineTo(x, y)
                    },
                    0L,
                    150L
                )
            )
            .build()

        Handler(Looper.getMainLooper()).post {
            dispatchGesture(gesture, null, null)
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Silf: Tap en X:$x, Y:$y", Toast.LENGTH_SHORT).show()
        }

        return rect
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
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        clickAt(rect.exactCenterX(), rect.exactCenterY())
                        return rect
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

    /**
     * Despacha un gesto de desplazamiento (swipe) hacia arriba o hacia abajo con GestureDescription.
     * UP: Desliza desde la parte inferior hacia la superior (scroll hacia abajo en contenido).
     * DOWN: Desliza desde la parte superior hacia la inferior (scroll hacia arriba en contenido).
     */
    fun performSwipe(isUp: Boolean): Boolean {
        Log.i(TAG, "Despachando swipe por GestureDescription: isUp=$isUp")
        return try {
            val dm = resources.displayMetrics
            val width = dm.widthPixels.toFloat()
            val height = dm.heightPixels.toFloat()
            val startX = width / 2f
            val startY = if (isUp) height * 0.75f else height * 0.25f
            val endY = if (isUp) height * 0.25f else height * 0.75f

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            if (Looper.myLooper() == Looper.getMainLooper()) {
                val dispatched = dispatchGesture(gesture, null, null)
                Toast.makeText(this, "Silf: Swipe ${if (isUp) "UP" else "DOWN"}", Toast.LENGTH_SHORT).show()
                dispatched
            } else {
                Handler(Looper.getMainLooper()).post {
                    dispatchGesture(gesture, null, null)
                    Toast.makeText(this, "Silf: Swipe ${if (isUp) "UP" else "DOWN"}", Toast.LENGTH_SHORT).show()
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error despachando swipe: ${e.message}", e)
            false
        }
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
         * Permite ejecutar un swipe (UP o DOWN) desde cualquier ViewModel o componente.
         */
        fun performSwipe(isUp: Boolean): Boolean {
            return instance?.performSwipe(isUp) == true
        }

        /**
         * Obtiene el snapshot congelado de la pantalla activa para el prompt.
         * Si ya existe un snapshot previo y coordinateMap tiene elementos, se conservan intactos
         * para no borrar la pantalla que el usuario estaba viendo antes de abrir Silf.
         */
        fun getFreshScreenSnapshot(): String {
            val inst = instance ?: return _screenSnapshot.value
            if (_screenSnapshot.value.isNotBlank() && inst.coordinateMap.isNotEmpty()) {
                Log.d(TAG, "getFreshScreenSnapshot: usando snapshot congelado con ${inst.coordinateMap.size} coordenadas")
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

        private var lastToastTime = 0L

        /**
         * Emite un Toast temporal que indica cuántos nodos están guardados en coordinateMap.
         */
        fun showMemoryDebugToast(fallbackContext: Context? = null) {
            val now = System.currentTimeMillis()
            if (now - lastToastTime < 1000L) return
            lastToastTime = now

            val inst = instance
            val count = inst?.coordinateMap?.size ?: 0
            val ctx = inst ?: fallbackContext ?: return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "Silf: $count nodos en memoria", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Emite un Toast con la cantidad actual de nodos en memoria.
     */
    fun showMemoryDebugToast() {
        val size = coordinateMap.size
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Silf: $size nodos en memoria", Toast.LENGTH_SHORT).show()
        }
    }
}


