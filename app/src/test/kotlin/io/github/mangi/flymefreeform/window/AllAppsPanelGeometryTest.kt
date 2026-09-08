package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllAppsPanelGeometryTest {
    private val dimensions = AllAppsPanelGeometry.Dimensions(360, 640, 578, 16, 40, 12)

    @Test fun portraitMatchesNativeDimensionsAndMirrorsItsAnchor() {
        val left = AllAppsPanelGeometry.calculate(411, 896, dimensions, AllAppsPanelGeometry.Mode.Portrait, true)
        val right = AllAppsPanelGeometry.calculate(411, 896, dimensions, AllAppsPanelGeometry.Mode.Portrait, false)
        assertEquals(AllAppsPanelGeometry.Bounds(12, 159, 360, 578), left)
        assertEquals(411 - left.left - left.width, right.left)
        assertEquals(left.top, right.top)
    }

    @Test fun keyboardAndCutoutNeverPlaceThePanelOutsideTheSafeArea() {
        for (mode in AllAppsPanelGeometry.Mode.entries) {
            val b = AllAppsPanelGeometry.calculate(411, 896, dimensions, mode, false, 25, 30, 0, 360)
            assertTrue(b.left >= 25 && b.top >= 30)
            assertTrue(b.left + b.width <= 411)
            assertTrue(b.top + b.height <= 536)
        }
    }

    @Test fun tabletopKeepsContentBelowTheFold() {
        val b = AllAppsPanelGeometry.calculate(900, 700, dimensions, AllAppsPanelGeometry.Mode.Tabletop, true)
        assertEquals(366, b.top)
        assertEquals(318, b.height)
    }

    @Test fun tinyWindowsRemainBounded() {
        val b = AllAppsPanelGeometry.calculate(20, 30, dimensions, AllAppsPanelGeometry.Mode.Portrait, true)
        assertTrue(b.width in 1..20 && b.height in 1..30)
        assertTrue(b.left + b.width <= 20 && b.top + b.height <= 30)
    }
}
