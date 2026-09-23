package io.github.mangi.flymefreeform.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        val activation = engine.move(0, 1, 25f, 1985f, config)
        assertTrue(activation is GestureAction.Activate)
        activation as GestureAction.Activate
        assertEquals(CornerSide.Left, activation.side)
        assertEquals(5f, activation.originX, 0f)
        assertEquals(1995f, activation.originY, 0f)
        assertEquals(25f, activation.x, 0f)
        assertEquals(1985f, activation.y, 0f)
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
    fun protectedSwipePromptsOnceAndCannotOpenOrCommitMenu() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, requiresConfirmation = true)
        assertEquals(GestureAction.RequireRepeat, engine.move(0, 1, 25f, 1985f, config, 100L))
        assertTrue(engine.isClaimed)
        assertEquals(GestureAction.Ignore, engine.move(0, 1, 35f, 1975f, config, 150L))
        engine.setSelection(1)
        assertEquals(GestureAction.Ignore, engine.up(0))
        assertFalse(engine.isClaimed)
    }

    @Test
    fun secondSwipeOnSameSideActivatesAndConsumesConfirmation() {
        val engine = CornerGestureEngine()
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 100L))
        assertTrue(swipe(engine, 2100L) is GestureAction.Activate)
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 2200L))
    }

    @Test
    fun confirmationExpiresAndMustBeRepeatedOnSameSide() {
        val expired = CornerGestureEngine()
        swipe(expired, 100L)
        assertEquals(GestureAction.RequireRepeat, swipe(expired, 2101L))

        val switched = CornerGestureEngine()
        swipe(switched, 100L)
        assertEquals(GestureAction.RequireRepeat, swipe(switched, 200L, right = true))
        assertTrue(swipe(switched, 300L, right = true) is GestureAction.Activate)
    }

    @Test
    fun invalidGesturesDoNotCountAsFirstSwipe() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, requiresConfirmation = true)
        engine.move(0, 1, 15f, 1992f, config, 100L)
        engine.up(0)
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 200L))

        engine.resetConfirmation()
        engine.down(0, 5f, 1995f, config, requiresConfirmation = true)
        engine.move(0, 2, 25f, 1985f, config, 300L)
        engine.up(0)
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 400L))
    }

    @Test
    fun cancelledFirstSwipeDoesNotUnlockNextSwipe() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, requiresConfirmation = true)
        engine.move(0, 1, 25f, 1985f, config, 100L)
        engine.cancel()
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 200L))
    }

    @Test
    fun missingUpDoesNotTurnOneStreamIntoTwoSwipes() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config, requiresConfirmation = true)
        engine.move(0, 1, 25f, 1985f, config, 100L)
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 200L))
    }

    @Test
    fun environmentResetAndUnprotectedGestureDiscardOldConfirmation() {
        val engine = CornerGestureEngine()
        swipe(engine, 100L)
        engine.resetConfirmation()
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 200L))
        engine.down(0, 5f, 1995f, config)
        assertTrue(engine.move(0, 1, 25f, 1985f, config, 300L) is GestureAction.Activate)
        engine.up(0)
        assertEquals(GestureAction.RequireRepeat, swipe(engine, 400L))
    }

    private fun swipe(engine: CornerGestureEngine, time: Long, right: Boolean = false): GestureAction {
        engine.down(0, if (right) 995f else 5f, 1995f, config, requiresConfirmation = true)
        val action = engine.move(0, 1, if (right) 975f else 25f, 1985f, config, time)
        engine.up(0)
        return action
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
