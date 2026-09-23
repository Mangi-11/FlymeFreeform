package io.github.mangi.flymefreeform.gesture

import java.lang.ref.WeakReference

/** 只接受同一小窗横条内的两次短点击；第二次抬手才关闭，避免把拖动当成双击。 */
internal class HandleDoubleTapGestureEngine {
    private var active: Tap? = null
    private var previous: Tap? = null
    private var previousUpTime = 0L
    private var secondTap = false

    fun begin(
        task: Any,
        pointerId: Int,
        x: Float,
        y: Float,
        time: Long,
        doubleTapTimeout: Long,
        doubleTapSlop: Float,
    ) {
        val first = previous
        secondTap = active == null && first != null && first.task.get() === task &&
            time >= previousUpTime && time - previousUpTime <= doubleTapTimeout &&
            within(first.x, first.y, x, y, doubleTapSlop)
        previous = null
        active = Tap(WeakReference(task), pointerId, x, y, time)
    }

    fun move(task: Any, pointerId: Int, x: Float, y: Float, touchSlop: Float) {
        val tap = active ?: return
        if (tap.task.get() !== task || tap.pointerId != pointerId ||
            !within(tap.x, tap.y, x, y, touchSlop)
        ) reset()
    }

    fun finish(
        task: Any,
        pointerId: Int,
        x: Float,
        y: Float,
        time: Long,
        touchSlop: Float,
        longPressTimeout: Long,
    ): Boolean {
        val tap = active
        val close = secondTap
        active = null
        secondTap = false
        if (tap == null || tap.task.get() !== task || tap.pointerId != pointerId ||
            time < tap.time || time - tap.time >= longPressTimeout ||
            !within(tap.x, tap.y, x, y, touchSlop)
        ) {
            reset()
            return false
        }
        if (!close) {
            previous = tap
            previousUpTime = time
        }
        return close
    }

    fun reset() {
        active = null
        previous = null
        previousUpTime = 0L
        secondTap = false
    }

    private fun within(x1: Float, y1: Float, x2: Float, y2: Float, slop: Float): Boolean {
        val dx = x2 - x1
        val dy = y2 - y1
        return slop >= 0f && dx * dx + dy * dy <= slop * slop
    }

    private data class Tap(
        val task: WeakReference<Any>,
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val time: Long,
    )
}
