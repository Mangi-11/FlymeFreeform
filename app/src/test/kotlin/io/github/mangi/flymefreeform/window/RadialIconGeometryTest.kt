package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.config.RadialMenuSettings
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.RadialGeometry
import io.github.mangi.flymefreeform.gesture.RadialMenuGeometry
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialIconGeometryTest {
    @Test
    fun configuredDpDimensionsDoNotChangeWithItemCountOrLargerScreens() {
        val settings = RadialMenuSettings(iconSizeDp = 48, radiusDp = 320, ringCount = 3)
        for (width in listOf(400f, 600f)) {
            val capacity = RadialMenuGeometry.calculate(settings, width.toInt()).capacity
            val expected = RadialIconGeometry.fit(width, 900f, 1f, OverlaySafeInsets(), 1, settings)
            assertEquals(48f, expected.iconDiameter, 0.001f)
            for (count in 1..capacity) {
                assertEquals(expected, RadialIconGeometry.fit(width, 900f, 1f, OverlaySafeInsets(), count, settings))
            }
        }
    }

    @Test
    fun equivalentDpWindowsHaveEquivalentGeometryAcrossDensitiesAndRotation() {
        val settings = RadialMenuSettings(ringCount = 3)
        val baseline = RadialIconGeometry.fit(400f, 890f, 1f, OverlaySafeInsets(), 10, settings)
        for (density in listOf(1f, 2.75f, 3f, 3.5f, 4f)) {
            for ((width, height) in listOf(400f to 890f, 890f to 400f)) {
                val actual = RadialIconGeometry.fit(width * density, height * density, density, OverlaySafeInsets(), 10, settings)
                assertEquals(baseline.radius, actual.radius / density, 0.001f)
                assertEquals(baseline.iconDiameter, actual.iconDiameter / density, 0.001f)
                assertEquals(baseline.rings.map { it.capacity }, actual.rings.map { it.capacity })
            }
        }
    }

    @Test
    fun everyRingFitsBothCornersWithoutOverlappingAndEverySlotCanBeSelected() {
        for (icon in listOf(24, 44, 72)) {
            for (radius in listOf(110, 272, 400)) {
                for (gap in listOf(0, 12, 48)) for (itemGap in listOf(0, 24, 48)) {
                    val settings = RadialMenuSettings(icon, radius, gap, Int.MAX_VALUE, itemGap)
                    val menu = RadialMenuGeometry.calculate(settings, 400)
                    for (insets in listOf(OverlaySafeInsets(), OverlaySafeInsets(100f, 50f, 110f, 60f))) {
                        val metrics = RadialIconGeometry.fit(400f, 890f, 1f, insets, menu.capacity, settings)
                        for (count in setOf(1, (menu.capacity / 2).coerceAtLeast(1), menu.capacity)) {
                            for (side in CornerSide.entries) {
                                val layout = RadialGeometry.layout(side,
                                    400f - insets.left - insets.right, 890f - insets.top - insets.bottom,
                                    metrics.radius, count, insets.left, insets.top, metrics.rings)
                                assertEquals(count, layout.itemCenters.size)
                                val extent = metrics.iconDiameter / 2f + metrics.itemPadding
                                layout.itemCenters.forEachIndexed { index, center ->
                                    assertTrue(center.x - extent >= insets.left - 0.001f)
                                    assertTrue(center.x + extent <= 400f - insets.right + 0.001f)
                                    assertTrue(center.y - extent >= insets.top - 0.001f)
                                    assertTrue(center.y + extent <= 890f - insets.bottom + 0.001f)
                                    layout.itemCenters.drop(index + 1).forEach { other ->
                                        assertTrue(hypot(center.x - other.x, center.y - other.y) >= 2f * extent - 0.001f)
                                    }
                                    assertEquals(index, RadialGeometry.selection(layout, center.x, center.y,
                                        (index - 1).takeIf { it >= 0 }, metrics.selectionEnterRadius, metrics.selectionKeepRadius))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun exhaustedSafeAreaProducesHiddenGeometry() {
        val metrics = RadialIconGeometry.fit(400f, 890f, 1f, OverlaySafeInsets(left = 400f), 7)
        assertEquals(0f, metrics.radius, 0f)
        assertEquals(0f, metrics.iconDiameter, 0f)
    }
}
