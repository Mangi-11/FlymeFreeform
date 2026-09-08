package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialEntryMotionTest {
    @Test
    fun opensFromTheCornerAndSettlesAtTheFixedGeometry() {
        val start = RadialEntryMotion.sample(0f)
        val end = RadialEntryMotion.sample(1f)

        assertEquals(0f, start.contentAlpha, 0f)
        assertEquals(0f, start.radialProgress, 0f)
        assertEquals(0f, start.horizontalOvershoot, 0f)
        assertEquals(-270f, start.rotationDegrees, 0f)
        assertEquals(0.8f, start.iconScale, 0f)
        assertEquals(1f, end.contentAlpha, 0f)
        assertEquals(1f, end.radialProgress, 0f)
        assertEquals(0f, end.horizontalOvershoot, 0f)
        assertEquals(0f, end.rotationDegrees, 0f)
        assertEquals(1f, end.iconScale, 0f)
    }

    @Test
    fun reachesTheExpandedPositionBeforeTheRebound() {
        val expanded = RadialEntryMotion.sample(130f / 380f)

        assertEquals(1f, expanded.contentAlpha, 0.0001f)
        assertEquals(1f, expanded.radialProgress, 0.0001f)
        assertEquals(1f, expanded.horizontalOvershoot, 0.0001f)
        assertEquals(5f, expanded.rotationDegrees, 0.0001f)
        assertEquals(1.05f, expanded.iconScale, 0.0001f)
    }

    @Test
    fun preservesTheVisibleRotationReboundAndSmallHorizontalOvershoot() {
        val rotationPeak = RadialEntryMotion.sample(180f / 380f)
        val horizontalRebound = RadialEntryMotion.sample(330f / 380f)

        assertTrue(rotationPeak.rotationDegrees > 15f)
        assertEquals(1f, rotationPeak.radialProgress, 0f)
        assertTrue(horizontalRebound.horizontalOvershoot < 0f)
        assertTrue(horizontalRebound.horizontalOvershoot > -0.1f)
    }

    @Test
    fun keepsTheTwoAnimationPhasesContinuous() {
        val before = RadialEntryMotion.sample(129.999f / 380f)
        val after = RadialEntryMotion.sample(130.001f / 380f)

        assertEquals(before.contentAlpha, after.contentAlpha, 0.001f)
        assertEquals(before.radialProgress, after.radialProgress, 0.001f)
        assertEquals(before.horizontalOvershoot, after.horizontalOvershoot, 0.001f)
        assertEquals(before.rotationDegrees, after.rotationDegrees, 0.01f)
        assertEquals(before.iconScale, after.iconScale, 0.001f)
    }

    @Test
    fun finishesPositionAndOpacityBeforeTheRotationSettles() {
        for (millis in 130..380) {
            val visuals = RadialEntryMotion.sample(millis / 380f)
            assertEquals(1f, visuals.contentAlpha, 0.0001f)
            assertEquals(1f, visuals.radialProgress, 0.0001f)
            assertTrue(visuals.iconScale in 1f..1.0501f)
        }
    }

    @Test
    fun selectionGrowsOnlyTheOuterRingWithoutOvershoot() {
        assertEquals(130L, RadialEntryMotion.SELECTION_DURATION_MILLIS)
        assertEquals(0f, RadialEntryMotion.selectionProgress(0f), 0f)
        assertEquals(1f, RadialEntryMotion.selectionProgress(1f), 0f)
        var previous = 0f
        for (step in 1..100) {
            val progress = RadialEntryMotion.selectionProgress(step / 100f)
            assertTrue(progress in previous..1f)
            previous = progress
        }
    }

    @Test
    fun clampsExternalTimeAtBothEnds() {
        assertEquals(RadialEntryMotion.sample(0f), RadialEntryMotion.sample(-1f))
        assertEquals(RadialEntryMotion.sample(1f), RadialEntryMotion.sample(2f))
        assertEquals(0f, RadialEntryMotion.selectionProgress(-1f), 0f)
        assertEquals(1f, RadialEntryMotion.selectionProgress(2f), 0f)
    }
}
