package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialExitMotionTest {
    @Test
    fun keepsItemsNearlyFullSizeWhileFadingThemAheadOfScrim() {
        val start = RadialExitMotion.sample(0f)
        val middle = RadialExitMotion.sample(0.5f)
        val end = RadialExitMotion.sample(1f)

        assertEquals(1f, start.contentAlpha, 0f)
        assertEquals(1f, start.scrimAlpha, 0f)
        assertEquals(1f, start.contentScale, 0f)
        assertTrue(middle.contentAlpha < middle.scrimAlpha)
        assertTrue(middle.contentScale in 0.94f..1f)
        assertEquals(0f, end.contentAlpha, 0f)
        assertEquals(0f, end.scrimAlpha, 0f)
        assertEquals(0.94f, end.contentScale, 0.0001f)
    }

    @Test
    fun clampsProgressAtBothEnds() {
        assertEquals(RadialExitMotion.sample(0f), RadialExitMotion.sample(-1f))
        assertEquals(RadialExitMotion.sample(1f), RadialExitMotion.sample(2f))
    }
}
