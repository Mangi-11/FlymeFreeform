package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
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
import io.github.mangi.flymefreeform.platform.coloros.ColorOsLauncherInputTarget
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** ColorOS Quickstep 输入入口；命中角落的指针流从 DOWN 起不再进入系统手势链。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class LauncherHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val gestureEngine = CornerGestureEngine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }
    private var environmentStarted = false
    private var activeConfig: CornerGestureConfig? = null
    private var activePointerId = -1
    private var suppressUntilTerminal = false
    private var pilfered = false
    private var inputMonitorField: Field? = null
    private var pilferPointers: ((Any) -> Unit)? = null
    private var lastClaimLogAt = -CLAIM_LOG_INTERVAL_MS
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    private val installationStarted = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        if (!installationStarted.compareAndSet(false, true)) return
        val apk = File(applicationInfo.sourceDir)
        val cache = File(applicationInfo.dataDir, "code_cache/flymefreeform/launcher-symbols")
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FlymeFreeform-LauncherDex").apply { isDaemon = true }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                executor.shutdownNow()
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_RESOLUTION_TIMEOUT")
            }
        }
        mainHandler.postDelayed(timeout, RESOLUTION_TIMEOUT_MS)
        // 解析前的事件完整交给桌面；发布 Hook 后也只从下一次 DOWN 开始接管。
        executor.execute {
            try {
                val target = ColorOsLauncherInputTarget.resolve(classLoader, apk, cache) { code, exception ->
                    module.log(if (exception == null) Log.INFO else Log.WARN, TAG, code, exception)
                }
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        installResolved(target)
                    }
                }
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_UNAVAILABLE", exception)
            } catch (exception: IOException) {
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_IO_FAILED", exception)
            } catch (exception: RuntimeException) {
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_RESOLUTION_FAILED", exception)
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
            } finally {
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) mainHandler.removeCallbacks(timeout)
                }
            }
        }
        // 只有这一项任务；完成后线程退出，不保留后台线程池或重试循环。
        executor.shutdown()
    }

    private fun installResolved(target: ColorOsLauncherInputTarget) {
        try {
            inputMonitorField = target.monitorField
            pilferPointers = { wrapper ->
                try {
                    target.pilferMethod.invoke(wrapper)
                } catch (exception: InvocationTargetException) {
                    throw exception.targetException
                }
            }
            module
                .hook(target.inputMethod)
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
        } catch (exception: IllegalArgumentException) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_HOOK_REJECTED", exception)
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

        if (!environmentState.isGestureAllowed()) cancelClaim()

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
        startEnvironment(owner)
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return null

        val metrics = owner.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return null
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

    private fun startEnvironment(owner: Context) {
        if (environmentStarted) return
        environmentStarted = true
        val start = {
            environmentState.start(owner)
            environmentState.observe {
                if (!environmentState.isGestureAllowed()) cancelClaim()
            }
            configuration.observe {
                mainHandler.post { if (!environmentState.isGestureAllowed()) cancelClaim() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else mainHandler.post { start() }
    }

    private fun cancelClaim() {
        gestureEngine.cancel()
        activeConfig = null
        // 已拦下 DOWN 的旧流继续收尾，不能把缺少 DOWN 的 MOVE/UP 交回桌面。
    }

    private fun forcePilferPointers(owner: Context) {
        try {
            val monitor = inputMonitorField?.get(owner) ?: return
            pilferPointers?.invoke(monitor)
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
        const val RESOLUTION_TIMEOUT_MS = 10_000L
        const val CLAIM_LOG_INTERVAL_MS = 2_000L
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }

}
