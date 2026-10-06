package com.silf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.silf.app.accessibility.SilfAccessibilityService
import com.silf.app.ui.screens.AssistantScreen
import com.silf.app.ui.theme.SilfTheme

/**
 * Actividad flotante para el Asistente Silf (Movicom).
 * Registrada para responder al intent android.intent.action.ASSIST.
 * Muestra únicamente una barra y panel flotante transparente (BottomSheet)
 * comunicándose directamente con el LLM cargado en RAM a través del ViewModel global.
 */
class AssistantActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SilfState.isAssistantActive = true
        SilfAccessibilityService.showMemoryDebugToast(this)
        setContent {
            SilfTheme {
                AssistantScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SilfState.isAssistantActive = true
        SilfAccessibilityService.showMemoryDebugToast(this)
    }

    override fun onDestroy() {
        SilfState.isAssistantActive = false
        super.onDestroy()
    }
}
