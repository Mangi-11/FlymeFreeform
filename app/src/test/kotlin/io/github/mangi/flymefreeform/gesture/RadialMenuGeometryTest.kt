package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.RadialMenuSettings
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class RadialMenuGeometryTest {
    @Test
    fun defaultsKeepSixPinsAndReserveMore() {
        val menu = RadialMenuGeometry.calculate(RadialMenuSettings(), 400)
        assertEquals(1, menu.rings.size)
        assertEquals(6, menu.pinnedCapacity)
        assertEquals(menu.pinnedCapacity + 1, menu.capacity)
    }

    @Test
    fun capacityRespondsToSizeRadiusSpacingAndSelectedRingCount() {
        fun capacity(icon: Int = 44, radius: Int = 272, gap: Int = 12, rings: Int = 3) =
            RadialMenuGeometry.calculate(RadialMenuSettings(icon, radius, gap, rings), 400).pinnedCapacity
        assertTrue(capacity(rings = 3) > 6)
        assertTrue(capacity(icon = 24) > capacity(icon = 72))
        assertTrue(capacity(radius = 400) > capacity(radius = 200))
        assertTrue(capacity(gap = 0) > capacity(gap = 48))
        assertTrue(capacity(rings = 3) > capacity(rings = 1))
        assertTrue(
            RadialMenuGeometry.calculate(RadialMenuSettings(itemGapDp = 0), 400).capacity >
                RadialMenuGeometry.calculate(RadialMenuSettings(itemGapDp = 48), 400).capacity,
        )
    }

    @Test
    fun sameRingGapChangesPositionsEvenBeforeCapacityChanges() {
        val compact = RadialMenuGeometry.calculate(RadialMenuSettings(itemGapDp = 0), 400)
        val spaced = RadialMenuGeometry.calculate(RadialMenuSettings(itemGapDp = 4), 400)
        assertEquals(compact.capacity, spaced.capacity)
        val compactLayout = RadialGeometry.layout(CornerSide.Left, 400f, 890f,
            compact.rings.first().radius, compact.capacity, rings = compact.rings)
        val spacedLayout = RadialGeometry.layout(CornerSide.Left, 400f, 890f,
            spaced.rings.first().radius, spaced.capacity, rings = spaced.rings)
        val first = spacedLayout.itemCenters[0]
        val second = spacedLayout.itemCenters[1]
        val diameterWithPadding = spaced.settings.iconSizeDp + 2f * RadialMenuSettings.ITEM_PADDING_DP
        assertEquals(4f, hypot(first.x - second.x, first.y - second.y) - diameterWithPadding, 0.001f)
        assertTrue(hypot(first.x - compactLayout.itemCenters[0].x,
            first.y - compactLayout.itemCenters[0].y) > 0.1f)
    }

    @Test
    fun dimensionsClampAndChangingInputsReducesRingRange() {
        val large = RadialMenuSettings(iconSizeDp = 24, radiusDp = 900, ringGapDp = 0, ringCount = Int.MAX_VALUE).normalized(400)
        assertEquals(400, large.radiusDp)
        assertEquals(large.maxRingCount(), large.ringCount)
        for (smaller in listOf(large.copy(iconSizeDp = 72), large.copy(radiusDp = 140), large.copy(ringGapDp = 48))) {
            val normalized = smaller.normalized(400)
            assertTrue(normalized.maxRingCount() < large.maxRingCount())
            assertEquals(normalized.maxRingCount(), normalized.ringCount)
        }
        val invalid = RadialMenuSettings(-20, -1, -50, -2).normalized(400)
        assertEquals(24, invalid.iconSizeDp)
        assertEquals(0, invalid.ringGapDp)
        assertEquals(1, invalid.ringCount)
        assertEquals(invalid.minimumRadiusDp(), invalid.radiusDp)
    }

    @Test
    fun moreStaysOnOuterRingAndMirrorsWithApplicationOrder() {
        val menu = RadialMenuGeometry.calculate(RadialMenuSettings(ringCount = 3), 400)
        val left = RadialGeometry.layout(CornerSide.Left, 400f, 890f, menu.rings.first().radius,
            menu.capacity, rings = menu.rings)
        val right = RadialGeometry.layout(CornerSide.Right, 400f, 890f, menu.rings.first().radius,
            menu.capacity, rings = menu.rings)
        left.itemCenters.zip(right.itemCenters).forEach { (l, r) ->
            assertEquals(400f, l.x + r.x, 0.001f)
            assertEquals(l.y, r.y, 0.001f)
        }
        val more = left.itemCenters.last()
        assertEquals(menu.rings.first().radius, hypot(more.x, more.y - 890f), 0.001f)
        assertTrue(left.itemCenters.dropLast(1).any { hypot(it.x, it.y - 890f) < menu.rings.first().radius - 1f })
    }
}
