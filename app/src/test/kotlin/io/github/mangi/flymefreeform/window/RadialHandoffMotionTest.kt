package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialHandoffMotionTest {
    @Test
    fun freezesReleasedGeometryWhileContentFadesInPlace() {
        val start = RadialHandoffMotion.sample(0.82f, 0f, 0f)
        val middle = RadialHandoffMotion.sample(0.82f, 0.5f, 0.6f)
        val end = RadialHandoffMotion.sample(0.82f, 1f, 1f)

        assertEquals(0.82f, start.revealProgress, 0f)
        assertEquals(start.revealProgress, middle.revealProgress, 0f)
        assertEquals(start.revealProgress, end.revealProgress, 0f)
        assertEquals(1f, start.contentAlpha, 0f)
        assertTrue(middle.contentAlpha in 0f..1f)
        assertTrue(middle.contentAlpha < start.contentAlpha)
        assertEquals(0f, end.contentAlpha, 0f)
        assertEquals(1f, start.contentScale, 0f)
        assertTrue(middle.contentScale in 0.94f..1f)
        assertEquals(0.94f, end.contentScale, 0.0001f)
    }

    @Test
    fun keepsScrimContinuousUntilPanelTakesOver() {
        val start = RadialHandoffMotion.sample(0.78f, 0f, 0f)
        val beforeTakeover = RadialHandoffMotion.sample(0.78f, 0.5f, 0.65f)
        val afterTakeover = RadialHandoffMotion.sample(0.78f, 0.8f, 0.9f)

        assertEquals(0.78f, start.scrimProgress, 0f)
        assertEquals(start.scrimProgress, beforeTakeover.scrimProgress, 0f)
        assertEquals(0.9f, afterTakeover.scrimProgress, 0f)
    }

    @Test
    fun clampsAllExternalProgressValues() {
        val below = RadialHandoffMotion.sample(-1f, -1f, -1f)
        val above = RadialHandoffMotion.sample(2f, 2f, 2f)

        assertEquals(0f, below.revealProgress, 0f)
        assertEquals(0f, below.scrimProgress, 0f)
        assertEquals(1f, above.revealProgress, 0f)
        assertEquals(1f, above.scrimProgress, 0f)
        assertEquals(0f, above.contentAlpha, 0f)
    }
}
