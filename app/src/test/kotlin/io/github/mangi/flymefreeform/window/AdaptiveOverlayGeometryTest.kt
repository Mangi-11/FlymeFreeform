package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveOverlayGeometryTest {
    private val phoneInsets = OverlaySafeInsets(left = 176f, top = 176f, right = 176f, bottom = 176f)

    @Test
    fun radialFitsActualBarsWhilePanelKeepsItsLargerSafeMargins() {
        val radialInsets = OverlaySafeInsets(top = 40f, bottom = 24f)
        val panelInsets = OverlaySafeInsets(left = 80f, top = 80f, right = 80f, bottom = 80f)
        val metrics = AdaptiveOverlayGeometry.calculate(
            width = 400f, height = 890f, safeInsets = panelInsets, systemIconSize = 64f,
            density = 1f, radialItemCount = 7, radialInsets = radialInsets,
            fontScale = 1f, panelItemCount = 40, anchorOnLeft = true,
        )
        assertEquals(242f, metrics.radial.radius, 0.001f)
        assertTrue(metrics.panel.bounds.left >= panelInsets.left)
        val layout = io.github.mangi.flymefreeform.gesture.RadialGeometry.layout(
            io.github.mangi.flymefreeform.gesture.CornerSide.Left,
            400f, 890f - radialInsets.top - radialInsets.bottom, metrics.radial.radius, 7,
            radialInsets.left, radialInsets.top,
        )
        assertEquals(0f, layout.origin.x, 0f)
        assertEquals(866f, layout.origin.y, 0f)
    }

    @Test
    fun panelRemainsInsideSafeContentBounds() {
        val metrics = phoneMetrics(fontScale = 1f, panelItemCount = 40)
        val bounds = metrics.panel.bounds

        assertTrue(bounds.left >= phoneInsets.left)
        assertTrue(bounds.top >= phoneInsets.top)
        assertTrue(bounds.right <= PHONE_WIDTH - phoneInsets.right)
        assertTrue(bounds.bottom <= PHONE_HEIGHT - phoneInsets.bottom)
        assertTrue(metrics.panel.columns > 0)
        assertTrue(metrics.panel.maxScroll > 0f)
    }

    @Test
    fun largeFontsReduceColumnsInsteadOfClippingTheSafeBounds() {
        val normal = phoneMetrics(fontScale = 1f, panelItemCount = 40)
        val large = phoneMetrics(fontScale = 2f, panelItemCount = 40)

        assertTrue(large.panel.columns <= normal.panel.columns)
        assertTrue(large.panel.labelTextSize > normal.panel.labelTextSize)
        assertTrue(large.panel.bounds.right <= PHONE_WIDTH - phoneInsets.right)
    }

    @Test
    fun shortCatalogDoesNotReserveUnusedGridColumns() {
        val short = phoneMetrics(fontScale = 1f, panelItemCount = 2)
        val long = phoneMetrics(fontScale = 1f, panelItemCount = 40)

        assertEquals(2, short.panel.columns)
        assertTrue(short.panel.bounds.width < long.panel.bounds.width)
    }

    @Test
    fun panelSeparatesIconAndLabelAndKeepsTextPaddingLocalToEachCell() {
        val panel = phoneMetrics(fontScale = 1f, panelItemCount = 40).panel
        val contentWidth = panel.bounds.width - panel.contentHorizontalPadding * 2
        val cellWidth = contentWidth / panel.columns

        assertTrue(panel.labelTopOffset > panel.iconDiameter / 2)
        assertTrue(panel.cellHeight > panel.iconDiameter + panel.labelTextSize)
        assertTrue(cellWidth - panel.horizontalTextPadding * 2 > panel.iconDiameter)
    }

    @Test
    fun horizontalSafeInsetsDoNotShrinkTheGestureArc() {
        val edgeToEdge =
            AdaptiveOverlayGeometry.calculate(
                density = 3f,
                radialItemCount = 7,
                width = PHONE_WIDTH,
                height = PHONE_HEIGHT,
                safeInsets = OverlaySafeInsets(top = 176f, bottom = 176f),
                systemIconSize = 192f,
                fontScale = 1f,
                panelItemCount = 40,
                anchorOnLeft = true,
            )
        val inset = phoneMetrics(fontScale = 1f, panelItemCount = 40)

        assertEquals(edgeToEdge.radial.radius, inset.radial.radius, 0.001f)
        assertEquals(edgeToEdge.radial.plateDiameter, inset.radial.plateDiameter, 0.001f)
    }

    @Test
    fun compactWindowStillKeepsPanelInsideAvailableSpace() {
        val insets = OverlaySafeInsets(left = 40f, top = 40f, right = 40f, bottom = 40f)
        val panel =
            AdaptiveOverlayGeometry.calculate(
                density = 3f,
                radialItemCount = 7,
                width = 200f,
                height = 300f,
                safeInsets = insets,
                systemIconSize = 120f,
                fontScale = 2f,
                panelItemCount = 20,
                anchorOnLeft = true,
            ).panel

        assertTrue(panel.bounds.left >= insets.left)
        assertTrue(panel.bounds.top >= insets.top)
        assertTrue(panel.bounds.right <= 200f - insets.right)
        assertTrue(panel.bounds.bottom <= 300f - insets.bottom)
    }

    @Test
    fun widerPortraitWindowAddsColumnsFromAvailableWidth() {
        val phone = phoneMetrics(fontScale = 1f, panelItemCount = 60)
        val wide =
            AdaptiveOverlayGeometry.calculate(
                density = 3f,
                radialItemCount = 7,
                width = 2200f,
                height = 2800f,
                safeInsets = OverlaySafeInsets(left = 120f, top = 120f, right = 120f, bottom = 120f),
                systemIconSize = 192f,
                fontScale = 1f,
                panelItemCount = 60,
                anchorOnLeft = true,
            )

        assertTrue(wide.panel.columns > phone.panel.columns)
        assertTrue(wide.panel.bounds.right <= 2200f - 120f)
    }

    @Test
    fun panelViewportReturnsOnlyVisibleRows() {
        val panel = phoneMetrics(fontScale = 1f, panelItemCount = 100).panel

        val visible = panel.visibleItemRange(itemCount = 100, scroll = 0f)

        assertEquals(0, visible.first)
        assertTrue(visible.count() < 100)
    }

    @Test
    fun panelViewportIncludesFinalItemAtMaximumScroll() {
        val panel = phoneMetrics(fontScale = 1f, panelItemCount = 100).panel

        val visible = panel.visibleItemRange(itemCount = 100, scroll = panel.maxScroll)

        assertTrue(99 in visible)
    }

    @Test
    fun panelViewportIsEmptyWithoutApps() {
        val panel = phoneMetrics(fontScale = 1f, panelItemCount = 0).panel

        assertTrue(panel.visibleItemRange(itemCount = 0, scroll = 0f).isEmpty())
    }

    private fun phoneMetrics(fontScale: Float, panelItemCount: Int): AdaptiveOverlayMetrics =
        AdaptiveOverlayGeometry.calculate(
            density = 3f,
            radialItemCount = 7,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT,
            safeInsets = phoneInsets,
            systemIconSize = 192f,
            fontScale = fontScale,
            panelItemCount = panelItemCount,
            anchorOnLeft = false,
        )

    private companion object {
        const val PHONE_WIDTH = 1440f
        const val PHONE_HEIGHT = 3136f
    }
}
