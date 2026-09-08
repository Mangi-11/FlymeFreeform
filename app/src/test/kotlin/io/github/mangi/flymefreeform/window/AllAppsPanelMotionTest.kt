package io.github.mangi.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllAppsPanelMotionTest {
    @Test fun exitShrinksAroundTheSameCenterWhileFadingFasterThanScale() {
        assertEquals(AllAppsPanelMotion.Frame(1f, 1f), AllAppsPanelMotion.exit(0f))
        val frame = AllAppsPanelMotion.exit(0.15f)
        assertEquals(0.1790f, frame.alpha, 0.001f)
        assertEquals(0.5227f, frame.scale, 0.001f)
        assertTrue(frame.alpha < (frame.scale - 0.3f) / 0.7f)
    }

    @Test fun exitNeverOvershootsAndIsInvisibleBeforeTheCleanupDeadline() {
        var previous = AllAppsPanelMotion.exit(0f)
        for (millis in 1..1000) {
            val frame = AllAppsPanelMotion.exit(millis / 1000f)
            assertTrue(frame.alpha <= previous.alpha)
            assertTrue(frame.scale <= previous.scale && frame.scale >= 0.3f)
            previous = frame
        }
        assertTrue(AllAppsPanelMotion.exit(0.5f).alpha < AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD)
    }

    @Test fun entryStartsInvisibleAndConvergesWithoutGrowingBeyondThePanel() {
        assertEquals(AllAppsPanelMotion.Frame(0f, 0.3f), AllAppsPanelMotion.enter(0f))
        var previous = AllAppsPanelMotion.enter(0f)
        for (millis in 1..1000) {
            val frame = AllAppsPanelMotion.enter(millis / 1000f)
            assertTrue(frame.alpha >= previous.alpha && frame.alpha <= 1f)
            assertTrue(frame.scale >= previous.scale && frame.scale <= 1f)
            previous = frame
        }
        assertTrue(previous.scale > 0.999f)
    }
}
