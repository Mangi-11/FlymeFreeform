package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.config.RadialMenuSettings
import io.github.mangi.flymefreeform.gesture.RadialMenuGeometry
import kotlin.math.floor

/** 按用户设置计算几何；安全区受限时整组等比缩放，保持圈数、容量和命中位置一致。 */
internal object RadialIconGeometry {
    fun fit(
        width: Float,
        height: Float,
        density: Float,
        safeInsets: OverlaySafeInsets,
        itemCount: Int,
        settings: RadialMenuSettings = RadialMenuSettings(),
        shortEdgeDp: Int = floor(minOf(width, height) / density).toInt(),
    ): RadialVisualMetrics {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(density.isFinite() && density > 0f)
        require(itemCount >= 1)
        val menu = RadialMenuGeometry.calculate(settings, shortEdgeDp)
        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(0f)
        val requestedExtent = menu.settings.radiusDp * density
        val fitScale = minOf(1f, safeWidth / requestedExtent, safeHeight / requestedExtent)
        val unit = density * fitScale
        val diameter = menu.settings.iconSizeDp * unit
        return RadialVisualMetrics(
            radius = menu.rings.first().radius * unit,
            plateDiameter = diameter,
            iconDiameter = diameter,
            selectionEnterRadius = diameter * 0.65f,
            selectionKeepRadius = diameter * 0.8f,
            itemPadding = RadialMenuSettings.ITEM_PADDING_DP * unit,
            pixelsPerBaseDp = unit,
            rings = menu.rings.map { it.copy(radius = it.radius * unit) },
        )
    }
}
