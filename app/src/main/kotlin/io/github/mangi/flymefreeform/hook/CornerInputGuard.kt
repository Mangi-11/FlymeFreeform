package io.github.mangi.flymefreeform.hook

import android.view.MotionEvent
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.GestureAction

internal enum class InputDisposition {
    PassThrough,
    PilferAndSuppress,
    Suppress,
}

/** SystemUI 与 Launcher 只负责在确认后让出同一指针流，不负责展示或启动。 */
internal class CornerInputGuard(
    private val touchSlop: Float,
    private val displaySize: () -> Pair<Float, Float>,
    private val environmentAllowed: () -> Boolean,
) {
    private val engine = CornerGestureEngine()
    private var suppressUntilTerminal = false
    private var streamAllowed = false

    fun onMotionEvent(event: MotionEvent, settings: ModuleSettingsSnapshot): InputDisposition {
        if (suppressUntilTerminal) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressUntilTerminal = false
            }
            return InputDisposition.Suppress
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            streamAllowed = settings.enabled && environmentAllowed()
            if (!streamAllowed) {
                engine.cancel()
                return InputDisposition.PassThrough
            }
        } else if (!streamAllowed) {
            return InputDisposition.PassThrough
        }
        if (!settings.enabled) {
            if (engine.isClaimed) {
                engine.cancel()
                suppressUntilTerminal =
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                return InputDisposition.Suppress
            }
            return InputDisposition.PassThrough
        }
        val (width, height) = displaySize()
        if (width <= 0f || height <= 0f || width > height) {
            val wasClaimed = engine.isClaimed
            engine.cancel()
            if (wasClaimed) {
                suppressUntilTerminal =
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                return InputDisposition.Suppress
            }
            streamAllowed = false
            return InputDisposition.PassThrough
        }
        val config =
            AdaptiveCornerGestureConfig.create(
                displayWidth = width,
                displayHeight = height,
                touchSlop = touchSlop,
                leftEnabled = settings.leftCornerEnabled,
                rightEnabled = settings.rightCornerEnabled,
            )
        val wasClaimed = engine.isClaimed
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    engine.down(event.getPointerId(0), event.x, event.y, config)
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
                    engine.move(
                        event.getPointerId(index),
                        event.pointerCount,
                        event.getX(index),
                        event.getY(index),
                        config,
                    )
                }
                MotionEvent.ACTION_UP -> engine.up(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> engine.cancel()
                else -> if (engine.isClaimed) engine.cancel() else GestureAction.PassThrough
            }
        val disposition = when (action) {
            is GestureAction.Activate -> InputDisposition.PilferAndSuppress
            is GestureAction.Update, is GestureAction.Commit -> InputDisposition.Suppress
            GestureAction.Cancel -> {
                if (wasClaimed) {
                    suppressUntilTerminal =
                        event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                    InputDisposition.Suppress
                } else {
                    InputDisposition.PassThrough
                }
            }
            GestureAction.Ignore, GestureAction.PassThrough -> InputDisposition.PassThrough
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            streamAllowed = false
        }
        return disposition
    }

    fun abandon() {
        engine.cancel()
        suppressUntilTerminal = false
        streamAllowed = false
    }
}
