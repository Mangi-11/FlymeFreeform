package io.github.mangi.flymefreeform.window

internal data class RadialEntryVisuals(
    val contentAlpha: Float,
    val radialProgress: Float,
    /** Multiplier for the horizontal, inward overshoot distance; may become negative. */
    val horizontalOvershoot: Float,
    val rotationDegrees: Float,
    val iconScale: Float,
)

/** A shared corner expansion followed by a short rotation and translation rebound. */
internal object RadialEntryMotion {
    const val DURATION_MILLIS = 380L
    const val SELECTION_DURATION_MILLIS = 130L
    const val HORIZONTAL_OVERSHOOT_DP = 6f
    const val SELECTION_RING_ALPHA = 64f / 255f

    private const val EXPANSION_MILLIS = 130f
    private const val REBOUND_MILLIS = 250f

    private val fade = CubicMotionCurve(0.33f, 0f, 0.67f, 1f)
    private val expandRotation = CubicMotionCurve(0.24f, 0.17f, 0.53f, 0.82f)
    private val expandScale = CubicMotionCurve(0.24f, 0.74f, 0.53f, 0.92f)
    private val expandPosition = CubicMotionCurve(0.24f, 0.55f, 0.53f, 0.8f)
    private val reboundRotation = CubicMotionCurve(0.19f, -7.6f, 0.48f, 1f)
    private val reboundScale = CubicMotionCurve(0.19f, 0.31f, 0.48f, 1f)
    private val reboundPosition = CubicMotionCurve(0.19f, 1.23f, 0.67f, 1f)
    private val selection = CubicMotionCurve(0.33f, 0f, 0.66f, 1f)

    fun sample(linearProgress: Float): RadialEntryVisuals {
        val elapsed = linearProgress.coerceIn(0f, 1f) * DURATION_MILLIS
        if (elapsed <= EXPANSION_MILLIS) {
            val progress = elapsed / EXPANSION_MILLIS
            val travel = expandPosition.sample(progress)
            return RadialEntryVisuals(
                contentAlpha = fade.sample(progress),
                radialProgress = travel,
                horizontalOvershoot = travel,
                rotationDegrees = -270f + 275f * expandRotation.sample(progress),
                iconScale = 0.8f + 0.25f * expandScale.sample(progress),
            )
        }
        val progress = (elapsed - EXPANSION_MILLIS) / REBOUND_MILLIS
        return RadialEntryVisuals(
            contentAlpha = 1f,
            radialProgress = 1f,
            horizontalOvershoot = 1f - reboundPosition.sample(progress),
            rotationDegrees = 5f * (1f - reboundRotation.sample(progress)),
            iconScale = 1f + 0.05f * (1f - reboundScale.sample(progress)),
        )
    }

    /** Animates ring thickness only; the selected icon retains its size. */
    fun selectionProgress(linearProgress: Float): Float = selection.sample(linearProgress)

}

/** Evaluates time-based cubic motion while preserving values outside the unit interval. */
internal class CubicMotionCurve(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) {
    fun sample(linearProgress: Float): Float {
        val x = linearProgress.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        var low = 0f
        var high = 1f
        repeat(20) {
            val parameter = (low + high) * 0.5f
            if (coordinate(parameter, x1, x2) < x) {
                low = parameter
            } else {
                high = parameter
            }
        }
        // Time is bounded, but the curve's output must retain its overshoot.
        return coordinate((low + high) * 0.5f, y1, y2)
    }

    private fun coordinate(parameter: Float, first: Float, second: Float): Float {
        val remainder = 1f - parameter
        return 3f * remainder * remainder * parameter * first +
            3f * remainder * parameter * parameter * second +
            parameter * parameter * parameter
    }
}
