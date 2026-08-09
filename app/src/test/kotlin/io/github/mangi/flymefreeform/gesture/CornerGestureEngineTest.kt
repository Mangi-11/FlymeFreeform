package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerGestureEngineTest {
    private val config =
        CornerGestureConfig(
            displayWidth = 1000f,
            displayHeight = 2000f,
            triggerRadius = 100f,
            inwardThreshold = 14f,
            upwardThreshold = 4f,
            reverseTolerance = 8f,
            leftEnabled = true,
            rightEnabled = true,
        )

    @Test
    fun leftAndRightRequireMirroredInwardMovement() {
        val left = CornerGestureEngine()
        left.down(0, 5f, 1995f, config)
        assertTrue(left.move(0, 1, 20f, 1989f, config) is GestureAction.Activate)

        val right = CornerGestureEngine()
        right.down(0, 995f, 1995f, config)
        assertTrue(right.move(0, 1, 980f, 1989f, config) is GestureAction.Activate)
    }

    @Test
    fun reverseAndSecondPointerCancelWithoutClaiming() {
        val reverse = CornerGestureEngine()
        reverse.down(0, 5f, 1995f, config)
        assertTrue(reverse.move(0, 1, -5f, 1995f, config) is GestureAction.PassThrough)
        assertFalse(reverse.isClaimed)

        val multi = CornerGestureEngine()
        multi.down(0, 5f, 1995f, config)
        assertTrue(multi.move(0, 2, 30f, 1980f, config) is GestureAction.PassThrough)
        assertFalse(multi.isClaimed)
    }

    @Test
    fun repeatedMovesActivateOnlyOnceThenUpdate() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config)
        assertTrue(engine.move(0, 1, 25f, 1985f, config) is GestureAction.Activate)
        assertTrue(engine.move(0, 1, 35f, 1975f, config) is GestureAction.Update)
        assertTrue(engine.up(0) is GestureAction.Commit)
    }

    @Test
    fun tapAndSubThresholdMovementNeverClaimInput() {
        val engine = CornerGestureEngine()

        assertTrue(engine.down(0, 5f, 1995f, config) is GestureAction.PassThrough)
        assertTrue(engine.move(0, 1, 15f, 1992f, config) is GestureAction.PassThrough)
        assertTrue(engine.up(0) is GestureAction.PassThrough)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun cancelAfterActivationReportsCancellationAndResets() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config)
        engine.move(0, 1, 25f, 1985f, config)
        assertTrue(engine.cancel() is GestureAction.Cancel)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun secondPointerCancelsAnActivatedMenu() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config)
        engine.move(0, 1, 25f, 1985f, config)

        assertTrue(engine.move(0, 2, 30f, 1980f, config) is GestureAction.Cancel)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun configuredRangeUsesDpWhileMovementThresholdsIgnoreRange() {
        val compact =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 800f,
                displayHeight = 1600f,
                touchSlop = 10f,
                density = 2f,
                triggerRangeDp = 84,
                leftEnabled = true,
                rightEnabled = true,
            )
        val wide =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 1600f,
                displayHeight = 2400f,
                touchSlop = 10f,
                density = 2f,
                triggerRangeDp = 160,
                leftEnabled = true,
                rightEnabled = true,
            )
        val largerTouchSlop =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 800f,
                displayHeight = 1600f,
                touchSlop = 30f,
                density = 2f,
                triggerRangeDp = 84,
                leftEnabled = true,
                rightEnabled = true,
            )

        assertTrue(wide.triggerRadius > compact.triggerRadius)
        assertTrue(largerTouchSlop.triggerRadius == compact.triggerRadius)
        assertTrue(wide.inwardThreshold == compact.inwardThreshold)
        assertTrue(wide.upwardThreshold == compact.upwardThreshold)
        assertTrue(largerTouchSlop.inwardThreshold > compact.inwardThreshold)
    }
}
