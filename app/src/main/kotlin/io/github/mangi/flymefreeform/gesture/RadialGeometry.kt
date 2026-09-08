package io.github.mangi.flymefreeform.gesture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class GesturePoint(val x: Float, val y: Float)

internal data class RadialLayout(
    val side: CornerSide,
    val origin: GesturePoint,
    val radius: Float,
    val itemCenters: List<GesturePoint>,
)

internal object RadialGeometry {
    private const val SPAN_DEGREES = 84f
    private const val SAFE_DEGREES = 3f

    fun layout(
        side: CornerSide,
        width: Float,
        height: Float,
        radius: Float,
        itemCount: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): RadialLayout {
        val origin = GesturePoint(offsetX + if (side == CornerSide.Left) 0f else width, offsetY + height)
        if (itemCount <= 0) return RadialLayout(side, origin, radius, emptyList())
        val step = SPAN_DEGREES / itemCount
        val centers =
            List(itemCount) { index ->
                // 列表末项是“更多”：它占最靠近角落的 0 号槽；应用从 1 号槽开始
                // 向上展开，避免任何应用的圆心落在屏幕边缘而被裁掉一半。
                val slot = if (index == itemCount - 1) 0 else index + 1
                val angle =
                    if (side == CornerSide.Left) {
                        -SAFE_DEGREES - (slot + 0.5f) * step
                    } else {
                        -180f + SAFE_DEGREES + (slot + 0.5f) * step
                    }
                val radians = angle * PI.toFloat() / 180f
                GesturePoint(
                    x = origin.x + cos(radians) * radius,
                    y = origin.y + sin(radians) * radius,
                )
            }
        return RadialLayout(side, origin, radius, centers)
    }

    fun selection(
        layout: RadialLayout,
        x: Float,
        y: Float,
        previous: Int?,
        enterRadius: Float,
        keepRadius: Float,
    ): Int? {
        if (layout.radius <= 0f) return null
        if (previous != null && previous in layout.itemCenters.indices) {
            val center = layout.itemCenters[previous]
            if (hypot(x - center.x, y - center.y) <= keepRadius) return previous
        }
        return layout.itemCenters
            .indices
            .minByOrNull { index ->
                val center = layout.itemCenters[index]
                hypot(x - center.x, y - center.y)
            }
            ?.takeIf { index ->
                val center = layout.itemCenters[index]
                hypot(x - center.x, y - center.y) <= enterRadius
            }
    }

    fun polarAngle(layout: RadialLayout, point: GesturePoint): Float =
        atan2(point.y - layout.origin.y, point.x - layout.origin.x)
}
