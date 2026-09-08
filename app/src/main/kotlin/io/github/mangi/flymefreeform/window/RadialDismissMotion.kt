package io.github.mangi.flymefreeform.window

internal data class RadialDismissVisuals(
    val contentAlpha: Float,
    val radialProgress: Float,
    val horizontalOvershoot: Float,
    val rotationDegrees: Float,
    val iconScale: Float,
)

/** Reverses the corner expansion after a brief rotation and horizontal anticipation. */
internal object RadialDismissMotion {
    const val DURATION_MILLIS = 200L

    private const val PHASE_MILLIS = 100f
    private val fade = CubicMotionCurve(0.33f, 0f, 0.67f, 1f)
    private val anticipateRotation = CubicMotionCurve(0.17f, 0f, 0.53f, -6.56f)
    private val anticipateScale = CubicMotionCurve(0.17f, 0f, 0.53f, 0.7f)
    private val anticipatePosition = CubicMotionCurve(0.33f, 0f, 0.53f, -0.22f)
    private val collapseRotation = CubicMotionCurve(0.19f, -0.06f, 0.32f, 1f)
    private val collapseScale = CubicMotionCurve(0.19f, 0f, 0.32f, 1f)
    private val collapsePosition = CubicMotionCurve(0.19f, 0.06f, 0.32f, 1f)

    fun sample(linearProgress: Float): RadialDismissVisuals {
        val elapsed = linearProgress.coerceIn(0f, 1f) * DURATION_MILLIS
        if (elapsed <= PHASE_MILLIS) {
            val progress = elapsed / PHASE_MILLIS
            return RadialDismissVisuals(
                contentAlpha = 1f - fade.sample(progress),
                radialProgress = 1f,
                horizontalOvershoot = anticipatePosition.sample(progress),
                rotationDegrees = 5f * anticipateRotation.sample(progress),
                iconScale = 1f + 0.05f * anticipateScale.sample(progress),
            )
        }
        val progress = (elapsed - PHASE_MILLIS) / PHASE_MILLIS
        val remainingDistance = 1f - collapsePosition.sample(progress)
        return RadialDismissVisuals(
            contentAlpha = 0f,
            radialProgress = remainingDistance,
            horizontalOvershoot = remainingDistance,
            rotationDegrees = 5f - 275f * collapseRotation.sample(progress),
            iconScale = 0.8f + 0.25f * (1f - collapseScale.sample(progress)),
        )
    }
}
