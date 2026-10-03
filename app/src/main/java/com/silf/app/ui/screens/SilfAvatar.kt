package com.silf.app.ui.screens

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Si el archivo aun no existe, la vista permanece vacia y transparente sin fallar.
 * Animaciones esperadas en el .riv: "idle" (reposo) y "talk" (generando).
 */
@Composable
fun SilfAvatar(isGenerating: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            RiveAnimationView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                val resId = context.resources.getIdentifier(
                    AVATAR_RESOURCE_NAME, "raw", context.packageName
                )
                if (resId != 0) {
                    runCatching {
                        setRiveResource(
                            resId = resId,
                            fit = Fit.COVER,
                            alignment = Alignment.CENTER,
                            autoplay = false
                        )
                        play(ANIM_IDLE, Loop.LOOP)
                    }
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
