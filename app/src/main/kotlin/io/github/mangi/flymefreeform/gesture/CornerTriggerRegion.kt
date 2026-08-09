package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.ModulePreferences
import kotlin.math.hypot

/** 左右下角共享的四分之一圆起点区域。 */
internal object CornerTriggerRegion {
    fun radiusPx(rangeDp: Int, density: Float): Float {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        return ModulePreferences.coerceCornerTriggerRangeDp(rangeDp) * safeDensity
    }

    fun detectSide(
        x: Float,
        y: Float,
        displayWidth: Float,
        displayHeight: Float,
        radius: Float,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): CornerSide? {
        val fromBottom = displayHeight - y
        if (fromBottom < 0f || fromBottom > radius) return null
        if (leftEnabled && x >= 0f && hypot(x, fromBottom) <= radius) {
            return CornerSide.Left
        }
        val fromRight = displayWidth - x
        if (rightEnabled && fromRight >= 0f && hypot(fromRight, fromBottom) <= radius) {
            return CornerSide.Right
        }
        return null
    }
}
