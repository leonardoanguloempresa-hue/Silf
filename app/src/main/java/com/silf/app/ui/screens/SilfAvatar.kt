package com.silf.app.ui.screens

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
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

private const val AVATAR_RESOURCE_NAME = "silf_avatar"
private const val ANIM_IDLE = "idle"
private const val ANIM_TALK = "talk"

/**
 * Avatar Rive reactivo. Espera res/raw/silf_avatar.riv (se agrega manualmente).
 * Validacion estricta: si el recurso no existe (id == 0) NO se instancia RiveAnimationView
 * (un fallo nativo de inicializacion no se puede atrapar con runCatching).
 * Animaciones esperadas en el .riv: "idle" (reposo) y "talk" (generando).
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
            RiveAnimationView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                runCatching {
                    setRiveResource(
                        resId = resourceId,
                        fit = Fit.COVER,
                        alignment = Alignment.CENTER,
                        autoplay = false
                    )
                    play(ANIM_IDLE, Loop.LOOP)
                }
            }
        },
        update = { view ->
            val animation = if (isGenerating) ANIM_TALK else ANIM_IDLE
            runCatching {
                view.stop()
                view.play(animation, Loop.LOOP)
            }
        }
    )
}
