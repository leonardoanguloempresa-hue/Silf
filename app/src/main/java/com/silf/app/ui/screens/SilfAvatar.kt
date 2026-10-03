package com.silf.app.ui.screens

import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment as ComposeAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Alignment
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.Loop

private const val TAG = "SilfAvatar"
private const val AVATAR_RESOURCE_NAME = "silf_avatar"
private const val ANIM_IDLE = "idle"
private const val ANIM_TALK = "talk"

/**
 * Avatar Rive reactivo con protección total contra crashes.
 * Espera res/raw/silf_avatar.riv (se agrega manualmente).
 *
 * Protecciones:
 * 1. Validación temprana: Si el recurso no existe en res/raw, no se instancia Rive y se muestra un placeholder.
 * 2. Instanciación segura: La creación de RiveAnimationView y carga de recursos están encapsuladas en try/catch.
 * 3. Animaciones tolerantes a fallos: Si el .riv no contiene las animaciones esperadas ('idle' o 'talk'),
 *    la excepción se atrapa con e.printStackTrace() y la vista permanece estática o vacía sin provocar un crash.
 */
@Composable
fun SilfAvatar(isGenerating: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resourceId = remember {
        context.resources.getIdentifier(AVATAR_RESOURCE_NAME, "raw", context.packageName)
    }

    if (resourceId <= 0) {
        Box(
            modifier = modifier.border(1.dp, Color.Gray.copy(alpha = 0.5f)),
            contentAlignment = ComposeAlignment.Center
        ) {
            Text(text = "Falta añadir silf_avatar.riv", color = Color.Gray)
        }
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val container = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            try {
                val riveView = RiveAnimationView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                }

                try {
                    riveView.setRiveResource(
                        resId = resourceId,
                        fit = Fit.COVER,
                        alignment = Alignment.CENTER,
                        autoplay = false
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error al cargar recurso Rive silf_avatar.riv", e)
                    e.printStackTrace()
                } catch (t: Throwable) {
                    Log.e(TAG, "Fallo crítico al configurar recurso Rive", t)
                    t.printStackTrace()
                }

                try {
                    riveView.play(ANIM_IDLE, Loop.LOOP)
                } catch (e: Exception) {
                    Log.w(TAG, "No se encontró la animación '$ANIM_IDLE' en silf_avatar.riv", e)
                    e.printStackTrace()
                } catch (t: Throwable) {
                    Log.w(TAG, "Fallo al reproducir '$ANIM_IDLE'", t)
                    t.printStackTrace()
                }

                container.addView(riveView)
            } catch (e: Exception) {
                Log.e(TAG, "Error durante la instanciación de RiveAnimationView", e)
                e.printStackTrace()
            } catch (t: Throwable) {
                Log.e(TAG, "Fallo crítico al crear RiveAnimationView", t)
                t.printStackTrace()
            }

            container
        },
        update = { container ->
            val riveView = container.getChildAt(0) as? RiveAnimationView ?: return@AndroidView
            val animation = if (isGenerating) ANIM_TALK else ANIM_IDLE

            try {
                try {
                    riveView.stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                try {
                    riveView.play(animation, Loop.LOOP)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo reproducir la animación '$animation'", e)
                    e.printStackTrace()
                } catch (t: Throwable) {
                    Log.w(TAG, "Fallo al reproducir '$animation'", t)
                    t.printStackTrace()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    )
}
