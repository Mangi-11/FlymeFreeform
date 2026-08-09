package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion
import io.github.mangi.flymefreeform.gesture.GestureAction
import io.github.mangi.flymefreeform.gesture.GesturePhase
import java.lang.reflect.Field
import java.lang.reflect.Method

/** SystemUI 中的受信任 SPY 热区；只抢占触摸，菜单状态仍由 system_server 持有。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi", "RtlHardcoded", "WrongConstant")
internal class SystemUiCornerInputMonitor(
    private val context: Context,
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(WindowManager::class.java)
            ?: error("WindowManager unavailable")
    private val inputManager =
        context.getSystemService(InputManager::class.java)
            ?: error("InputManager unavailable")
    private val environmentState = GestureEnvironmentState(context)
    private val inputFeaturesField: Field =
        WindowManager.LayoutParams::class.java.getField("inputFeatures").apply {
            isAccessible = true
        }
    private val setTrustedOverlayMethod: Method =
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").apply {
            isAccessible = true
        }
    private val getViewRootImplMethod: Method =
        View::class.java.getDeclaredMethod("getViewRootImpl").apply {
            isAccessible = true
        }
    private val pilferPointersMethod: Method =
        InputManager::class.java.getDeclaredMethod("pilferPointers", IBinder::class.java).apply {
            isAccessible = true
        }
    private val bindings = linkedMapOf<CornerSide, CornerBinding>()

    private var settings = ModuleSettingsSnapshot(enabled = false)
    private var pendingSettings: ModuleSettingsSnapshot? = null
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    fun start() {
        configuration.observe { snapshot ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                acceptSettings(snapshot)
            } else {
                mainHandler.post { acceptSettings(snapshot) }
            }
        }
    }

    private fun acceptSettings(snapshot: ModuleSettingsSnapshot) {
        ensureMainThread()
        if (bindings.values.any { it.view.streamActive }) {
            pendingSettings = snapshot
            return
        }
        applySettings(snapshot)
    }

    private fun applySettings(snapshot: ModuleSettingsSnapshot) {
        ensureMainThread()
        settings = snapshot
        pendingSettings = null
        updateSide(CornerSide.Left, snapshot.enabled && snapshot.leftCornerEnabled)
        updateSide(CornerSide.Right, snapshot.enabled && snapshot.rightCornerEnabled)
    }

    private fun updateSide(side: CornerSide, shouldExist: Boolean) {
        val existing = bindings[side]
        if (!shouldExist) {
            if (existing != null) removeBinding(side, existing)
            return
        }

        val size =
            (CornerTriggerRegion.radiusPx(
                settings.cornerTriggerRangeDp,
                context.resources.displayMetrics.density,
            ) + 0.5f).toInt().coerceAtLeast(1)
        if (existing == null) {
            addBinding(side, size)
        } else if (existing.size != size) {
            existing.size = size
            existing.view.rangePx = size.toFloat()
            existing.view.rangeDp = settings.cornerTriggerRangeDp
            try {
                windowManager.updateViewLayout(existing.view, createLayoutParams(side, size))
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_UPDATE_FAILED", exception)
                removeBinding(side, existing)
            } catch (exception: RuntimeException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_UPDATE_FAILED", exception)
                removeBinding(side, existing)
            }
        }
    }

    private fun addBinding(side: CornerSide, size: Int) {
        val view =
            CornerGestureView(
                context = context,
                side = side,
                rangePx = size.toFloat(),
                rangeDp = settings.cornerTriggerRangeDp,
                canClaim = { canClaim(side) },
                pilfer = ::pilfer,
                onStreamFinished = ::finishStream,
            )
        try {
            windowManager.addView(view, createLayoutParams(side, size))
            bindings[side] = CornerBinding(view, size)
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_ADD_FAILED", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_ADD_FAILED", exception)
        }
    }

    private fun removeBinding(side: CornerSide, binding: CornerBinding) {
        bindings.remove(side)
        binding.view.resetTracking()
        try {
            windowManager.removeViewImmediate(binding.view)
        } catch (_: IllegalArgumentException) {
            return
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_REMOVE_FAILED", exception)
        }
    }

    private fun canClaim(side: CornerSide): Boolean =
        settings.enabled &&
            when (side) {
                CornerSide.Left -> settings.leftCornerEnabled
                CornerSide.Right -> settings.rightCornerEnabled
            } &&
            environmentState.isAllowed(refreshKeyguard = true)

    private fun pilfer(view: View): Boolean =
        try {
            val viewRoot = getViewRootImplMethod.invoke(view) ?: return false
            val inputToken =
                viewRoot.javaClass.getMethod("getInputToken").invoke(viewRoot) as? IBinder
                    ?: return false
            pilferPointersMethod.invoke(inputManager, inputToken)
            true
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
            false
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
            false
        }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_FAILURE_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_PILFER_FAILED", exception)
    }

    private fun finishStream() {
        mainHandler.post {
            if (bindings.values.any { it.view.streamActive }) return@post
            pendingSettings?.let(::applySettings)
        }
    }

    private fun createLayoutParams(side: CornerSide, size: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            size,
            size,
            CORNER_GESTURE_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity =
                Gravity.BOTTOM or
                    if (side == CornerSide.Left) Gravity.LEFT else Gravity.RIGHT
            title = "$CORNER_INPUT_CHANNEL_TITLE-${side.name}"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlayMethod.invoke(this)
            inputFeaturesField.setInt(
                this,
                inputFeaturesField.getInt(this) or INPUT_FEATURE_SPY,
            )
        }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Corner input windows must run on the SystemUI main thread"
        }
    }

    private data class CornerBinding(
        val view: CornerGestureView,
        var size: Int,
    )

    @SuppressLint("ClickableViewAccessibility")
    private class CornerGestureView(
        context: Context,
        private val side: CornerSide,
        var rangePx: Float,
        var rangeDp: Int,
        private val canClaim: () -> Boolean,
        private val pilfer: (View) -> Boolean,
        private val onStreamFinished: () -> Unit,
    ) : View(context) {
        private val gestureEngine = CornerGestureEngine()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val density = context.resources.displayMetrics.density
        private var activeConfig: CornerGestureConfig? = null
        private var activePointerId = -1
        private var tracking = false
        private var pilferAttempted = false

        val streamActive: Boolean
            get() = tracking

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setWillNotDraw(true)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                val wasActive = tracking
                resetTracking()
                if (wasActive) onStreamFinished()
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTracking()
                    val eligible =
                        event.pointerCount == 1 &&
                            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                            canClaim() &&
                            CornerTriggerRegion.detectSide(
                                x = event.x,
                                y = event.y,
                                displayWidth = width.toFloat(),
                                displayHeight = height.toFloat(),
                                radius = rangePx,
                                leftEnabled = side == CornerSide.Left,
                                rightEnabled = side == CornerSide.Right,
                            ) == side
                    if (eligible) {
                        val config =
                            AdaptiveCornerGestureConfig.create(
                                displayWidth = width.toFloat(),
                                displayHeight = height.toFloat(),
                                touchSlop = touchSlop,
                                density = density,
                                triggerRangeDp = rangeDp,
                                leftEnabled = side == CornerSide.Left,
                                rightEnabled = side == CornerSide.Right,
                            )
                        activePointerId = event.getPointerId(0)
                        gestureEngine.down(activePointerId, event.x, event.y, config)
                        if (gestureEngine.phase == GesturePhase.Armed) {
                            activeConfig = config
                            tracking = true
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        val config = activeConfig
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        val action =
                            if (config != null && pointerIndex >= 0) {
                                gestureEngine.move(
                                    pointerId = activePointerId,
                                    pointerCount = event.pointerCount,
                                    x = event.getX(pointerIndex),
                                    y = event.getY(pointerIndex),
                                    config = config,
                                )
                            } else {
                                gestureEngine.cancel()
                            }
                        if (!pilferAttempted && action is GestureAction.Activate) {
                            pilferAttempted = true
                            pilfer(this)
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (tracking) gestureEngine.cancel()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && tracking) {
                        gestureEngine.up(event.getPointerId(event.actionIndex))
                    }
                    resetTracking()
                    onStreamFinished()
                }

                else -> Unit
            }
            return true
        }

        fun resetTracking() {
            gestureEngine.cancel()
            activeConfig = null
            activePointerId = -1
            tracking = false
            pilferAttempted = false
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val INPUT_FEATURE_SPY = 1 shl 2
        const val CORNER_GESTURE_WINDOW_TYPE = 2024
        const val CORNER_INPUT_CHANNEL_TITLE = "FlymeFreeform-corner-input"
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}
