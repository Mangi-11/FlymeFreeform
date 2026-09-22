package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.RadialMenuSettings
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.floor

internal data class RadialRing(val radius: Float, val edgeAngle: Float, val capacity: Int, val itemAngle: Float)

/** 设置、示例和 Hook 使用同一个模型，容量包含一个“更多”入口。角度以弧度表示。 */
internal data class RadialMenuGeometry(
    val settings: RadialMenuSettings,
    val rings: List<RadialRing>,
) {
    val capacity: Int get() = rings.sumOf { it.capacity }
    val pinnedCapacity: Int get() = (capacity - 1).coerceAtLeast(0)

    companion object {
        fun calculate(settings: RadialMenuSettings, shortEdgeDp: Int): RadialMenuGeometry {
            val resolved = settings.normalized(shortEdgeDp)
            val extent = resolved.iconSizeDp / 2f + RadialMenuSettings.ITEM_PADDING_DP
            val outerCenter = resolved.radiusDp - extent - RadialMenuSettings.EDGE_MARGIN_DP
            val rings = List(resolved.ringCount) { index ->
                val radius = (outerCenter - index * resolved.ringPitchDp()).coerceAtLeast(extent)
                val edgeAngle = asin((extent / radius).coerceIn(0f, 1f))
                val span = (PI.toFloat() / 2f - 2f * edgeAngle).coerceAtLeast(0f)
                // 按弦长留出同圈净间距；描边也包含在图标占用范围内。
                val step = 2f * asin(((extent + resolved.itemGapDp / 2f) / radius).coerceIn(0f, 1f))
                val count = (floor(span / step + 0.00001f).toInt() + 1).coerceAtLeast(1)
                RadialRing(radius, edgeAngle, count, step)
            }
            return RadialMenuGeometry(resolved, rings)
        }
    }
}
