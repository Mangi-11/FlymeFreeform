package io.github.mangi.flymefreeform.window

internal data class RadialExitVisuals(
    val contentAlpha: Float,
    val scrimAlpha: Float,
    val contentScale: Float,
)

/** Keeps radial items in place while their content and scrim leave on separate timelines. */
internal object RadialExitMotion {
    const val DURATION_MILLIS = 240L
    const val DURATION_SECONDS = DURATION_MILLIS / 1_000f

    private const val CONTENT_END_FRACTION = 0.72f
    private const val MIN_CONTENT_SCALE = 0.94f

    fun sample(linearProgress: Float): RadialExitVisuals {
        val progress = linearProgress.coerceIn(0f, 1f)
        val contentProgress = smoothStep((progress / CONTENT_END_FRACTION).coerceIn(0f, 1f))
        val scrimProgress = smoothStep(progress)
        return RadialExitVisuals(
            contentAlpha = 1f - contentProgress,
            scrimAlpha = 1f - scrimProgress,
            contentScale = 1f - (1f - MIN_CONTENT_SCALE) * contentProgress,
        )
    }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)
}
