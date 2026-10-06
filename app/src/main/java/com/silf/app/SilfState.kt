package com.silf.app

/**
 * Estado global y reactivo de la aplicación Silf.
 * Mantiene flags de visibilidad y actividad de componentes como el Asistente flotante.
 */
object SilfState {
    /**
     * Indica si AssistantActivity está visible o en transición.
     * Cuando es true, SilfAccessibilityService congela el snapshot y el coordinateMap
     * para no sobrescribir la pantalla de la aplicación de fondo.
     * Pasa a false justo antes de despachar gestos en scheduleAction o al destruir AssistantActivity.
     */
    @Volatile
    var isAssistantActive: Boolean = false
}
