package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialDismissMotionTest {
    @Test
    fun startsAtTheRestingGeometryAndFinishesCollapsed() {
        val start = RadialDismissMotion.sample(0f)
        val end = RadialDismissMotion.sample(1f)

        assertEquals(1f, start.contentAlpha, 0f)
        assertEquals(1f, start.radialProgress, 0f)
        assertEquals(0f, start.horizontalOvershoot, 0f)
        assertEquals(0f, start.rotationDegrees, 0f)
        assertEquals(1f, start.iconScale, 0f)
        assertEquals(0f, end.contentAlpha, 0f)
        assertEquals(0f, end.radialProgress, 0f)
        assertEquals(0f, end.horizontalOvershoot, 0f)
        assertEquals(-270f, end.rotationDegrees, 0f)
        assertEquals(0.8f, end.iconScale, 0f)
    }

    @Test
    fun fadesOutBeforeCollapsingThePositions() {
        val halfway = RadialDismissMotion.sample(0.5f)
        val collapse = RadialDismissMotion.sample(0.75f)

        assertEquals(0f, halfway.contentAlpha, 0f)
        assertEquals(1f, halfway.radialProgress, 0f)
        assertEquals(1f, halfway.horizontalOvershoot, 0f)
        assertEquals(5f, halfway.rotationDegrees, 0f)
        assertEquals(1.05f, halfway.iconScale, 0.0001f)
        assertEquals(0f, collapse.contentAlpha, 0f)
        assertTrue(collapse.radialProgress in 0f..1f)
        assertEquals(collapse.radialProgress, collapse.horizontalOvershoot, 0f)
    }

    @Test
    fun preservesAnticipationBeforeTheInwardMotion() {
        val anticipation = RadialDismissMotion.sample(0.1f)

        assertTrue(anticipation.rotationDegrees < -5f)
        assertTrue(anticipation.horizontalOvershoot < 0f)
        assertTrue(anticipation.contentAlpha in 0f..1f)
        assertEquals(1f, anticipation.radialProgress, 0f)
    }

    @Test
    fun keepsTheTwoPhasesContinuous() {
        val before = RadialDismissMotion.sample(99.999f / 200f)
        val after = RadialDismissMotion.sample(100.001f / 200f)

        assertEquals(before.contentAlpha, after.contentAlpha, 0.001f)
        assertEquals(before.radialProgress, after.radialProgress, 0.001f)
        assertEquals(before.horizontalOvershoot, after.horizontalOvershoot, 0.001f)
        assertEquals(before.rotationDegrees, after.rotationDegrees, 0.01f)
        assertEquals(before.iconScale, after.iconScale, 0.001f)
    }

    @Test
    fun clampsExternalTimeAtBothEnds() {
        assertEquals(RadialDismissMotion.sample(0f), RadialDismissMotion.sample(-1f))
        assertEquals(RadialDismissMotion.sample(1f), RadialDismissMotion.sample(2f))
    }
}
