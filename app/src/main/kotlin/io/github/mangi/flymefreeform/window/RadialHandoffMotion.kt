package io.github.mangi.flymefreeform.window

import kotlin.math.max

internal data class RadialHandoffVisuals(
    val revealProgress: Float,
    val contentAlpha: Float,
    val contentScale: Float,
    val scrimProgress: Float,
)

/** Keeps the released radial geometry fixed while the panel takes over its visual state. */
internal object RadialHandoffMotion {
    fun sample(
        frozenRevealProgress: Float,
        handoffProgress: Float,
        panelProgress: Float,
    ): RadialHandoffVisuals {
        val reveal = frozenRevealProgress.coerceIn(0f, 1f)
        val exit = RadialExitMotion.sample(handoffProgress)
        return RadialHandoffVisuals(
            revealProgress = reveal,
            contentAlpha = exit.contentAlpha,
            contentScale = exit.contentScale,
            scrimProgress = max(reveal, panelProgress.coerceIn(0f, 1f)),
        )
    }
}
