package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.GesturePoint

internal data class ShortcutMenuBounds(val left: Float, val top: Float, val width: Float, val rowHeight: Float, val count: Int) {
    val right get() = left + width
    val bottom get() = top + rowHeight * count

    fun rowAt(x: Float, y: Float): Int? =
        if (x >= left && x <= right && y >= top && y < bottom) ((y - top) / rowHeight).toInt() else null
}

internal object ShortcutMenuGeometry {
    fun place(
        icon: GesturePoint,
        side: CornerSide,
        screenWidth: Float,
        screenHeight: Float,
        density: Float,
        count: Int,
        iconDiameter: Float,
    ): ShortcutMenuBounds {
        val margin = 12f * density
        val width = minOf(184f * density, (screenWidth - 2f * margin).coerceAtLeast(1f))
        val rowHeight = minOf(48f * density, (screenHeight - 2f * margin) / count).coerceAtLeast(1f)
        val gap = 10f * density
        val leftOfIcon = icon.x - iconDiameter / 2f - gap - width
        val rightOfIcon = icon.x + iconDiameter / 2f + gap
        val leftFits = leftOfIcon >= margin
        val rightFits = rightOfIcon + width <= screenWidth - margin
        val preferredLeft = when {
            side == CornerSide.Left && rightFits -> rightOfIcon
            side == CornerSide.Right && leftFits -> leftOfIcon
            leftFits -> leftOfIcon
            rightFits -> rightOfIcon
            else -> if (icon.x < screenWidth / 2f) rightOfIcon else leftOfIcon
        }
        val left = preferredLeft.coerceIn(margin, (screenWidth - margin - width).coerceAtLeast(margin))
        val height = rowHeight * count
        val top = (icon.y - height / 2f).coerceIn(margin, (screenHeight - margin - height).coerceAtLeast(margin))
        return ShortcutMenuBounds(left, top, width, rowHeight, count)
    }
}
