package io.github.mangi.flymefreeform.gesture

import kotlin.math.max

internal enum class CornerSide {
    Left,
    Right,
}

internal enum class GesturePhase {
    Idle,
    Armed,
    Revealing,
    Selecting,
    Committing,
    MorePanel,
    Cancelling,
}

internal sealed interface GestureAction {
    data object Ignore : GestureAction
    data object PassThrough : GestureAction
    data class Activate(val side: CornerSide, val x: Float, val y: Float) : GestureAction
    data class Update(val side: CornerSide, val x: Float, val y: Float) : GestureAction
    data class Commit(val selectedIndex: Int?) : GestureAction
    data object Cancel : GestureAction
}

internal data class CornerGestureConfig(
    val displayWidth: Float,
    val displayHeight: Float,
    val triggerRadius: Float,
    val inwardThreshold: Float,
    val upwardThreshold: Float,
    val reverseTolerance: Float,
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
)

internal object AdaptiveCornerGestureConfig {
    fun create(
        displayWidth: Float,
        displayHeight: Float,
        touchSlop: Float,
        density: Float,
        triggerRangeDp: Int,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): CornerGestureConfig {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val triggerRadius = CornerTriggerRegion.radiusPx(triggerRangeDp, safeDensity)
        return CornerGestureConfig(
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            triggerRadius = triggerRadius,
            inwardThreshold = max(touchSlop * INWARD_SLOP_MULTIPLIER, safeDensity * INWARD_DP),
            upwardThreshold = max(touchSlop * UPWARD_SLOP_MULTIPLIER, safeDensity * UPWARD_DP),
            reverseTolerance = max(touchSlop, safeDensity * REVERSE_TOLERANCE_DP),
            leftEnabled = leftEnabled,
            rightEnabled = rightEnabled,
        )
    }

    private const val INWARD_SLOP_MULTIPLIER = 1.75f
    private const val UPWARD_SLOP_MULTIPLIER = 0.50f
    private const val INWARD_DP = 14f
    private const val UPWARD_DP = 4f
    private const val REVERSE_TOLERANCE_DP = 8f
}

/** 单指角落手势状态机；不持有 MotionEvent，便于跨进程入口复用与单元测试。 */
internal class CornerGestureEngine {
    var phase: GesturePhase = GesturePhase.Idle
        private set
    var selectedIndex: Int? = null
        private set

    private var pointerId = -1
    private var originX = 0f
    private var originY = 0f
    private var side: CornerSide? = null
    private var claimed = false

    fun down(pointerId: Int, x: Float, y: Float, config: CornerGestureConfig): GestureAction {
        reset()
        val detectedSide = detectSide(x, y, config) ?: return GestureAction.PassThrough
        this.pointerId = pointerId
        originX = x
        originY = y
        side = detectedSide
        phase = GesturePhase.Armed
        return GestureAction.PassThrough
    }

    fun move(
        pointerId: Int,
        pointerCount: Int,
        x: Float,
        y: Float,
        config: CornerGestureConfig,
    ): GestureAction {
        val activeSide = side ?: return GestureAction.PassThrough
        if (pointerId != this.pointerId || pointerCount != 1) return cancel()

        val inward = if (activeSide == CornerSide.Left) x - originX else originX - x
        val upward = originY - y
        if (inward < -config.reverseTolerance || upward < -config.reverseTolerance) {
            return cancel()
        }
        if (!claimed) {
            if (inward < config.inwardThreshold || upward < config.upwardThreshold) {
                return GestureAction.PassThrough
            }
            claimed = true
            phase = GesturePhase.Revealing
            return GestureAction.Activate(activeSide, x, y)
        }

        phase = GesturePhase.Selecting
        return GestureAction.Update(activeSide, x, y)
    }

    fun up(pointerId: Int): GestureAction {
        if (pointerId != this.pointerId) return cancel()
        if (!claimed) {
            reset()
            return GestureAction.PassThrough
        }
        val selection = selectedIndex
        clearTracking(GesturePhase.Committing)
        val result = GestureAction.Commit(selection)
        return result
    }

    fun cancel(): GestureAction {
        val wasClaimed = claimed
        clearTracking(if (wasClaimed) GesturePhase.Cancelling else GesturePhase.Idle)
        return if (wasClaimed) GestureAction.Cancel else GestureAction.PassThrough
    }

    fun setSelection(index: Int?) {
        if (claimed) selectedIndex = index
    }

    val isClaimed: Boolean
        get() = claimed

    private fun detectSide(x: Float, y: Float, config: CornerGestureConfig): CornerSide? {
        return CornerTriggerRegion.detectSide(
            x = x,
            y = y,
            displayWidth = config.displayWidth,
            displayHeight = config.displayHeight,
            radius = config.triggerRadius,
            leftEnabled = config.leftEnabled,
            rightEnabled = config.rightEnabled,
        )
    }

    private fun reset() {
        clearTracking(GesturePhase.Idle)
    }

    private fun clearTracking(nextPhase: GesturePhase) {
        phase = nextPhase
        selectedIndex = null
        pointerId = -1
        originX = 0f
        originY = 0f
        side = null
        claimed = false
    }
}
