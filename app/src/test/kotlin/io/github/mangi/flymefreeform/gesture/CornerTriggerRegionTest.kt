package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CornerTriggerRegionTest {
    @Test
    fun radiusUsesClampedDpAndDensity() {
        assertEquals(48f, CornerTriggerRegion.radiusPx(8, 2f), 0f)
        assertEquals(168f, CornerTriggerRegion.radiusPx(84, 2f), 0f)
        assertEquals(320f, CornerTriggerRegion.radiusPx(200, 2f), 0f)
    }

    @Test
    fun detectsMirroredQuarterCirclesAndHonorsSideSwitches() {
        assertEquals(
            CornerSide.Left,
            CornerTriggerRegion.detectSide(30f, 1960f, 1000f, 2000f, 50f, true, true),
        )
        assertEquals(
            CornerSide.Right,
            CornerTriggerRegion.detectSide(970f, 1960f, 1000f, 2000f, 50f, true, true),
        )
        assertNull(
            CornerTriggerRegion.detectSide(30f, 1960f, 1000f, 2000f, 50f, false, true),
        )
        assertNull(
            CornerTriggerRegion.detectSide(40f, 1960f, 1000f, 2000f, 50f, true, true),
        )
    }
}
