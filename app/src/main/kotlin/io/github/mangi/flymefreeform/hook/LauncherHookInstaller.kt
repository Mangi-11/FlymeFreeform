package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion
import io.github.mangi.flymefreeform.gesture.GestureAction
import io.github.mangi.flymefreeform.gesture.GesturePhase
import java.lang.reflect.Field
import java.lang.reflect.Method

/** ColorOS Quickstep 输入入口；命中角落的指针流从 DOWN 起不再进入系统手势链。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class LauncherHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val gestureEngine = CornerGestureEngine()
    private var environmentState: GestureEnvironmentState? = null
    private var activeConfig: CornerGestureConfig? = null
    private var activePointerId = -1
    private var suppressUntilTerminal = false
    private var pilfered = false
    private var inputMonitorField: Field? = null
    private var forcePilferPointersMethod: Method? = null
    private var lastClaimLogAt = -CLAIM_LOG_INTERVAL_MS
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val serviceClass = classLoader.loadClass(TOUCH_SERVICE_CLASS)
            val inputMethod =
                serviceClass.getDeclaredMethod(INPUT_METHOD_NAME, InputEvent::class.java).apply {
                    isAccessible = true
                }
            val monitorClass = classLoader.loadClass(INPUT_MONITOR_CLASS)
            inputMonitorField =
                serviceClass.getDeclaredField(INPUT_MONITOR_FIELD).apply { isAccessible = true }
            forcePilferPointersMethod =
                monitorClass.getDeclaredMethod(FORCE_PILFER_METHOD).apply { isAccessible = true }

            module
                .hook(inputMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.launcher.corner_input_owner")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                        ?: return@intercept chain.proceed()
                    val owner = chain.thisObject as? Context
                        ?: return@intercept chain.proceed()
                    if (shouldSuppress(owner, event)) {
                        null
                    } else {
                        chain.proceed()
                    }
                }
            module.log(Log.INFO, TAG, "LAUNCHER_CORNER_INPUT_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun shouldSuppress(owner: Context, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetStream()
            val config = createEligibleConfig(owner, event) ?: return false
            activePointerId = event.getPointerId(0)
            gestureEngine.down(activePointerId, event.rawX, event.rawY, config)
            if (gestureEngine.phase == GesturePhase.Armed) {
                activeConfig = config
                suppressUntilTerminal = true
                return true
            }
            resetStream()
            return false
        }
        if (!suppressUntilTerminal) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val config = activeConfig
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (config != null && pointerIndex >= 0) {
                    val action =
                        gestureEngine.move(
                            pointerId = activePointerId,
                            pointerCount = event.pointerCount,
                            x = event.getRawX(pointerIndex),
                            y = event.getRawY(pointerIndex),
                            config = config,
                        )
                    if (!pilfered && action is GestureAction.Activate) {
                        forcePilferPointers(owner)
                        pilfered = true
                        logClaimed()
                    }
                } else {
                    gestureEngine.cancel()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> gestureEngine.cancel()

            MotionEvent.ACTION_UP -> {
                gestureEngine.up(event.getPointerId(event.actionIndex))
                resetStream()
            }

            MotionEvent.ACTION_CANCEL -> resetStream()

            else -> Unit
        }
        return true
    }

    private fun createEligibleConfig(
        owner: Context,
        event: MotionEvent,
    ): CornerGestureConfig? {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return null
        val settings = configuration.snapshot
        if (!configuration.isAvailable || !settings.enabled) return null
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return null
        if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return null
        val environment =
            environmentState ?: GestureEnvironmentState(owner).also { environmentState = it }
        if (!environment.isAllowed(refreshKeyguard = true)) return null

        val metrics = owner.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f || width > height) return null
        return AdaptiveCornerGestureConfig.create(
            displayWidth = width,
            displayHeight = height,
            touchSlop = ViewConfiguration.get(owner).scaledTouchSlop.toFloat(),
            density = metrics.density,
            triggerRangeDp = settings.cornerTriggerRangeDp,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ).takeIf { config ->
            CornerTriggerRegion.detectSide(
                x = event.rawX,
                y = event.rawY,
                displayWidth = config.displayWidth,
                displayHeight = config.displayHeight,
                radius = config.triggerRadius,
                leftEnabled = config.leftEnabled,
                rightEnabled = config.rightEnabled,
            ) != null
        }
    }

    private fun resetStream() {
        gestureEngine.cancel()
        activeConfig = null
        activePointerId = -1
        suppressUntilTerminal = false
        pilfered = false
    }

    private fun forcePilferPointers(owner: Context) {
        try {
            val monitor = inputMonitorField?.get(owner) ?: return
            forcePilferPointersMethod?.invoke(monitor)
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
        }
    }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_FAILURE_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_PILFER_FAILED", exception)
    }

    private fun logClaimed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaimLogAt < CLAIM_LOG_INTERVAL_MS) return
        lastClaimLogAt = now
        module.log(Log.INFO, TAG, "LAUNCHER_CORNER_INPUT_CLAIMED")
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val TOUCH_SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"
        const val INPUT_METHOD_NAME = "onInputEventInternal"
        const val INPUT_MONITOR_CLASS = "com.android.systemui.shared.system.InputMonitorCompat"
        const val INPUT_MONITOR_FIELD = "mInputMonitorCompat"
        const val FORCE_PILFER_METHOD = "forcePilferPointers"
        const val CLAIM_LOG_INTERVAL_MS = 2_000L
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}
