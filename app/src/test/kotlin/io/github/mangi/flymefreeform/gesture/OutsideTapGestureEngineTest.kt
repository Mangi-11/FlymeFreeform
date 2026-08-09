package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutsideTapGestureEngineTest {
    private val task = Any()

    @Test
    fun singleTapClosesAfterCapturedShortTap() {
        val engine = engine(OutsideTapCloseMode.SingleTap)
        engine.begin(task, 1, 100f, 200f, 10L, captured = true)

        assertTrue(engine.finish(task, 1, 102f, 202f, 100L, 500L, 8f, 300L, 40f))
    }

    @Test
    fun failedCaptureDoesNotClose() {
        val engine = engine(OutsideTapCloseMode.SingleTap)
        engine.begin(task, 1, 100f, 200f, 10L, captured = false)

        assertFalse(engine.finish(task, 1, 100f, 200f, 100L, 500L, 8f, 300L, 40f))
    }

    @Test
    fun doubleTapClosesOnlyOnNearbySecondTap() {
        val engine = engine(OutsideTapCloseMode.DoubleTap)
        engine.begin(task, 1, 100f, 200f, 10L, captured = true)
        assertFalse(engine.finish(task, 1, 100f, 200f, 50L, 500L, 8f, 300L, 40f))

        engine.begin(task, 1, 110f, 205f, 100L, captured = true)
        assertTrue(engine.finish(task, 1, 110f, 205f, 130L, 500L, 8f, 300L, 40f))
    }

    @Test
    fun doubleTapTimeoutStartsANewPair() {
        val engine = engine(OutsideTapCloseMode.DoubleTap)
        engine.begin(task, 1, 0f, 0f, 0L, captured = true)
        assertFalse(engine.finish(task, 1, 0f, 0f, 10L, 500L, 8f, 100L, 40f))

        engine.begin(task, 1, 0f, 0f, 200L, captured = true)
        assertFalse(engine.finish(task, 1, 0f, 0f, 210L, 500L, 8f, 100L, 40f))
    }

    @Test
    fun distantSecondTapAndCancelDoNotClose() {
        val engine = engine(OutsideTapCloseMode.DoubleTap)
        engine.begin(task, 1, 0f, 0f, 0L, captured = true)
        assertFalse(engine.finish(task, 1, 0f, 0f, 10L, 500L, 8f, 300L, 40f))

        engine.begin(task, 1, 100f, 100f, 20L, captured = true)
        assertFalse(engine.finish(task, 1, 100f, 100f, 30L, 500L, 8f, 300L, 40f))

        engine.begin(task, 1, 100f, 100f, 40L, captured = true)
        engine.interrupt()
        assertFalse(engine.finish(task, 1, 100f, 100f, 50L, 500L, 8f, 300L, 40f))
    }

    @Test
    fun movementMultiTouchAndLongPressCancelClose() {
        val moving = engine(OutsideTapCloseMode.SingleTap)
        moving.begin(task, 1, 0f, 0f, 0L, captured = true)
        moving.move(task, 1, 1, 20f, 0f, 8f)
        assertFalse(moving.finish(task, 1, 20f, 0f, 50L, 500L, 8f, 300L, 40f))

        val multiTouch = engine(OutsideTapCloseMode.SingleTap)
        multiTouch.begin(task, 1, 0f, 0f, 0L, captured = true)
        multiTouch.move(task, 1, 2, 0f, 0f, 8f)
        assertFalse(multiTouch.finish(task, 1, 0f, 0f, 50L, 500L, 8f, 300L, 40f))

        val longPress = engine(OutsideTapCloseMode.SingleTap)
        longPress.begin(task, 1, 0f, 0f, 0L, captured = true)
        assertFalse(longPress.finish(task, 1, 0f, 0f, 501L, 500L, 8f, 300L, 40f))
    }

    @Test
    fun taskAndModeChangesClearPendingDoubleTap() {
        val engine = engine(OutsideTapCloseMode.DoubleTap)
        engine.begin(task, 1, 0f, 0f, 0L, captured = true)
        assertFalse(engine.finish(task, 1, 0f, 0f, 10L, 500L, 8f, 300L, 40f))

        val otherTask = Any()
        engine.begin(otherTask, 1, 0f, 0f, 20L, captured = true)
        assertFalse(engine.finish(otherTask, 1, 0f, 0f, 30L, 500L, 8f, 300L, 40f))

        engine.updateMode(OutsideTapCloseMode.SingleTap)
        engine.updateMode(OutsideTapCloseMode.DoubleTap)
        engine.begin(otherTask, 1, 0f, 0f, 40L, captured = true)
        assertFalse(engine.finish(otherTask, 1, 0f, 0f, 50L, 500L, 8f, 300L, 40f))
    }

    private fun engine(mode: OutsideTapCloseMode) =
        OutsideTapGestureEngine().also { it.updateMode(mode) }
}
