package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.RadialGeometry
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialIconGeometryTest {
    private fun fit(width: Float = 400f, height: Float = 890f, density: Float = 1f,
                    insets: OverlaySafeInsets = OverlaySafeInsets(), count: Int = 7) =
        RadialIconGeometry.fit(width, height, density, insets, count)

    @Test
    fun baselineMatchesFixedDimensionsForEveryItemCount() {
        for (count in 1..7) {
            val metrics = fit(count = count)
            assertEquals(242f, metrics.radius, 0.001f)
            assertEquals(44f, metrics.iconDiameter, 0.001f)
            assertEquals(metrics.iconDiameter, metrics.plateDiameter, 0f)
            assertEquals(3.25f, metrics.itemPadding, 0.001f)
            assertEquals(1f, metrics.pixelsPerBaseDp, 0.001f)
        }
    }

    @Test
    fun largerScreensScaleRadiusIconsAndHitTargetsTogether() {
        val baseline = fit()
        for (factor in listOf(0.4f, 0.8f, 1.5f, 2.1f)) {
            val metrics = fit(width = 400f * factor, height = 890f * factor)
            assertEquals(baseline.radius * factor, metrics.radius, 0.001f)
            assertEquals(baseline.iconDiameter * factor, metrics.iconDiameter, 0.001f)
            assertEquals(baseline.selectionEnterRadius * factor, metrics.selectionEnterRadius, 0.001f)
            assertEquals(baseline.selectionKeepRadius * factor, metrics.selectionKeepRadius, 0.001f)
        }
    }

    @Test
    fun equivalentDpWindowsHaveEquivalentGeometryAcrossDensities() {
        val expected = fit()
        for (density in listOf(1f, 2.75f, 3f, 3.5f, 4f)) {
            val actual = fit(400f * density, 890f * density, density)
            assertEquals(expected.radius, actual.radius / density, 0.001f)
            assertEquals(expected.iconDiameter, actual.iconDiameter / density, 0.001f)
        }
        // 同一像素窗口改变系统显示大小，屏幕占比保持一致。
        assertEquals(fit(density = 1f), fit(density = 2f))
    }

    @Test
    fun rotationKeepsShortEdgeScale() {
        assertEquals(fit(400f, 890f), fit(890f, 400f))
    }

    @Test
    fun constrainedSpaceShrinksEverythingWithoutDependingOnCount() {
        val insets = OverlaySafeInsets(left = 170f, right = 170f, top = 50f, bottom = 100f)
        val full = fit()
        val narrow = fit(insets = insets)
        assertTrue(narrow.radius < full.radius)
        assertEquals(242f / 44f, narrow.radius / narrow.iconDiameter, 0.001f)
        assertEquals(0.9f, narrow.selectionEnterRadius / narrow.iconDiameter, 0.001f)
        assertEquals(1.25f, narrow.selectionKeepRadius / narrow.iconDiameter, 0.001f)
        for (count in 1..7) assertEquals(narrow, fit(insets = insets, count = count))
    }

    @Test
    fun selectionRingsStayInsideBothSafeCornersAndDoNotOverlap() {
        for ((width, height) in listOf(160f to 300f, 400f to 890f, 890f to 400f, 840f to 1100f)) {
            for (insets in listOf(OverlaySafeInsets(), OverlaySafeInsets(30f, 70f, 90f, 120f))) {
                for (count in 1..7) {
                    val metrics = fit(width, height, insets = insets, count = count)
                    val selectedRadius = metrics.iconDiameter / 2f
                    val padding = metrics.itemPadding
                    for (side in CornerSide.entries) {
                        val layout = RadialGeometry.layout(side,
                            width - insets.left - insets.right, height - insets.top - insets.bottom,
                            metrics.radius, count, insets.left, insets.top)
                        layout.itemCenters.forEachIndexed { index, center ->
                            val extent = selectedRadius + padding
                            assertTrue(center.x - extent >= insets.left - 0.001f)
                            assertTrue(center.x + extent <= width - insets.right + 0.001f)
                            assertTrue(center.y - extent >= insets.top - 0.001f)
                            assertTrue(center.y + extent <= height - insets.bottom + 0.001f)
                            layout.itemCenters.drop(index + 1).forEach { other ->
                                assertTrue(hypot(center.x - other.x, center.y - other.y) >=
                                    selectedRadius + padding + metrics.iconDiameter / 2f)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun exhaustedSafeAreaProducesHiddenGeometry() {
        val metrics = fit(insets = OverlaySafeInsets(left = 400f))
        assertEquals(0f, metrics.radius, 0f)
        assertEquals(0f, metrics.iconDiameter, 0f)
    }
}
