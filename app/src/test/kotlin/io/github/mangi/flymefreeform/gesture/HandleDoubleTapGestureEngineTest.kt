package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleDoubleTapGestureEngineTest {
    private val engine = HandleDoubleTapGestureEngine()
    private val task = Any()

    private fun down(time: Long, target: Any = task, x: Float = 100f, pointer: Int = 0) =
        engine.begin(target, pointer, x, 100f, time, 300L, 40f)

    private fun up(time: Long, target: Any = task, x: Float = 100f, pointer: Int = 0): Boolean =
        engine.finish(target, pointer, x, 100f, time, 8f, 500L)

    private fun firstTap() {
        down(0)
        assertFalse(up(40))
    }

    @Test fun closesOnlyAfterSecondUp() {
        firstTap()
        down(140)
        assertTrue(up(180))
        down(240)
        assertFalse(up(280))
    }

    @Test fun timeoutIsMeasuredFromFirstUpToSecondDown() {
        firstTap()
        down(340)
        assertTrue(up(450))
    }

    @Test fun expiredTapStartsNewPair() {
        firstTap()
        down(341)
        assertFalse(up(380))
        down(440)
        assertTrue(up(480))
    }

    @Test fun differentTasksCannotFormDoubleTap() {
        firstTap()
        val other = Any()
        down(140, other)
        assertFalse(up(180, other))
    }

    @Test fun distantTapsCannotClose() {
        firstTap()
        down(140, x = 141f)
        assertFalse(up(180, x = 141f))
    }

    @Test fun smallFingerJitterIsAllowed() {
        firstTap()
        down(140)
        engine.move(task, 0, 104f, 102f, 8f)
        assertTrue(up(180, x = 105f))
    }

    @Test fun draggingSecondTapOutAndBackDoesNotClose() {
        firstTap()
        down(140)
        engine.move(task, 0, 109f, 100f, 8f)
        engine.move(task, 0, 100f, 100f, 8f)
        assertFalse(up(180))
        down(240)
        assertFalse(up(280))
    }

    @Test fun firstDragDoesNotCountAsTap() {
        down(0)
        engine.move(task, 0, 140f, 100f, 8f)
        assertFalse(up(40))
        down(140)
        assertFalse(up(180))
    }

    @Test fun upDisplacementIsCheckedEvenWithoutMove() {
        firstTap()
        down(140)
        assertFalse(up(180, x = 109f))
    }

    @Test fun longPressCannotClose() {
        firstTap()
        down(140)
        assertFalse(up(640))
        down(700)
        assertFalse(up(740))
    }

    @Test fun resetCancelsActiveAndPendingTap() {
        // Hook 对取消、多指、设置变化、暂停以及横条外点击都使用 reset。
        firstTap()
        engine.reset()
        down(140)
        assertFalse(up(180))
        down(240)
        engine.reset()
        assertFalse(up(280))
    }

    @Test fun changedPointerOrTaskCancelsActiveTap() {
        firstTap()
        down(140)
        assertFalse(up(180, pointer = 1))
        firstTap()
        down(140)
        engine.move(Any(), 0, 100f, 100f, 8f)
        assertFalse(up(180))
    }

    @Test fun missingDownAndReversedTimestampsDoNotClose() {
        assertFalse(up(0))
        firstTap()
        down(20)
        assertFalse(up(30))
        down(140)
        assertFalse(up(130))
    }

    @Test fun nonFiniteCoordinatesFailClosed() {
        firstTap()
        down(140, x = Float.NaN)
        assertFalse(up(180, x = Float.NaN))
    }
}
