package io.github.mangi.flymefreeform.window

import kotlin.math.min

internal object AllAppsPanelGeometry {
    enum class Mode { Portrait, LargePortrait, Landscape, Tabletop }
    data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)
    data class Dimensions(val maxWidth: Int, val maxHeight: Int, val portraitHeight: Int, val minMargin: Int, val maxMargin: Int, val gap: Int)

    fun calculate(
        width: Int, height: Int, dimensions: Dimensions, mode: Mode, leftSide: Boolean,
        insetLeft: Int = 0, insetTop: Int = 0, insetRight: Int = 0, insetBottom: Int = 0,
    ): Bounds {
        val d = dimensions
        val usableWidth = (width - insetLeft - insetRight).coerceAtLeast(1)
        val usableHeight = (height - insetTop - insetBottom).coerceAtLeast(1)
        val panelWidth = min(usableWidth, min(d.maxWidth, width - 2 * d.minMargin)).coerceAtLeast(1)
        val desiredTop = when (mode) {
            Mode.Portrait -> (height - min(d.maxHeight, d.portraitHeight)) / 2
            Mode.LargePortrait -> d.maxMargin
            Mode.Landscape -> d.minMargin
            Mode.Tabletop -> height / 2 + d.minMargin
        }.coerceAtLeast(0)
        val desiredHeight = when (mode) {
            Mode.Portrait -> min(d.maxHeight, d.portraitHeight)
            Mode.LargePortrait -> min(d.maxHeight, height - 2 * d.maxMargin)
            else -> height - desiredTop - d.minMargin
        }
        val top = desiredTop.coerceIn(insetTop, maxOf(insetTop, height - insetBottom - 1))
        val panelHeight = min(desiredHeight, min(usableHeight, height - insetBottom - top)).coerceAtLeast(1)
        val left = (if (leftSide) d.gap else width - d.gap - panelWidth)
            .coerceIn(insetLeft, maxOf(insetLeft, width - insetRight - panelWidth))
        return Bounds(left, top, panelWidth, panelHeight)
    }
}
