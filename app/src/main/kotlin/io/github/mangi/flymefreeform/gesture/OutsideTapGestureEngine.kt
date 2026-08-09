package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.OutsideTapCloseMode

/** 与 Android/Hook 解耦的窗外短点击分类器。 */
internal class OutsideTapGestureEngine {
    private var mode = OutsideTapCloseMode.Disabled
    private var activeTap: ActiveTap? = null
    private var previousTap: CompletedTap? = null

    fun updateMode(nextMode: OutsideTapCloseMode) {
        if (mode == nextMode) return
        mode = nextMode
        reset()
    }

    fun begin(
        task: Any,
        pointerId: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        captured: Boolean,
    ) {
        activeTap = null
        if (mode == OutsideTapCloseMode.Disabled || !captured) {
            previousTap = null
            return
        }
        if (previousTap?.task !== task) previousTap = null
        activeTap = ActiveTap(task, pointerId, x, y, eventTime)
    }

    fun move(
        task: Any,
        pointerId: Int,
        pointerCount: Int,
        x: Float,
        y: Float,
        touchSlop: Float,
    ) {
        val active = activeTap ?: return
        if (active.task !== task || active.pointerId != pointerId || pointerCount != 1) {
            interrupt()
            return
        }
        val dx = x - active.downX
        val dy = y - active.downY
        if (dx * dx + dy * dy > touchSlop * touchSlop) interrupt()
    }

    fun finish(
        task: Any,
        pointerId: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        longPressTimeout: Long,
        touchSlop: Float,
        doubleTapTimeout: Long,
        doubleTapSlop: Float,
    ): Boolean {
        val active = activeTap
        activeTap = null
        if (
            active == null ||
                active.task !== task ||
                active.pointerId != pointerId ||
                eventTime < active.downTime ||
                eventTime - active.downTime > longPressTimeout
        ) {
            previousTap = null
            return false
        }
        val dx = x - active.downX
        val dy = y - active.downY
        if (dx * dx + dy * dy > touchSlop * touchSlop) {
            previousTap = null
            return false
        }
        return when (mode) {
            OutsideTapCloseMode.Disabled -> false
            OutsideTapCloseMode.SingleTap -> {
                previousTap = null
                true
            }
            OutsideTapCloseMode.DoubleTap -> finishDoubleTap(active.task, x, y, eventTime, doubleTapTimeout, doubleTapSlop)
        }
    }

    fun interrupt() {
        activeTap = null
        previousTap = null
    }

    fun reset() {
        activeTap = null
        previousTap = null
    }

    private fun finishDoubleTap(
        task: Any,
        x: Float,
        y: Float,
        eventTime: Long,
        doubleTapTimeout: Long,
        doubleTapSlop: Float,
    ): Boolean {
        val previous = previousTap
        val isSecondTap =
            previous != null &&
                previous.task === task &&
                eventTime >= previous.eventTime &&
                eventTime - previous.eventTime <= doubleTapTimeout &&
                distanceSquared(previous.x, previous.y, x, y) <= doubleTapSlop * doubleTapSlop
        if (isSecondTap) {
            previousTap = null
            return true
        }
        previousTap = CompletedTap(task, x, y, eventTime)
        return false
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return dx * dx + dy * dy
    }

    private data class ActiveTap(
        val task: Any,
        val pointerId: Int,
        val downX: Float,
        val downY: Float,
        val downTime: Long,
    )

    private data class CompletedTap(
        val task: Any,
        val x: Float,
        val y: Float,
        val eventTime: Long,
    )
}
