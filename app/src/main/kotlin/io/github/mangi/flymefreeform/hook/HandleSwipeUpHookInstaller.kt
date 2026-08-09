package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.HandleSwipeUpModeRemapper
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** 将 ColorOS 普通小窗底部手柄的原生上滑模式重映射为原生 MINI 模式。 */
@SuppressLint("PrivateApi")
internal class HandleSwipeUpHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val remapLogged = AtomicBoolean(false)
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val scaleManagerClass = classLoader.loadClass(SCALE_MANAGER_CLASS)
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val taskClass = classLoader.loadClass(TASK_CLASS)
            val getGestureMode =
                scaleManagerClass.getDeclaredMethod(
                    "getGestureMode",
                    Double::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Point::class.java,
                    Point::class.java,
                ).apply { isAccessible = true }
            val access =
                ColorOsHandleSwipeAccess(
                    scaleManagerClass = scaleManagerClass,
                    controllerClass = controllerClass,
                    taskClass = taskClass,
                )
            module
                .hook(getGestureMode)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.handle_up_to_mini")
                .intercept { chain ->
                    val originalResult = chain.proceed()
                    val originalMode = (originalResult as? Number)?.toInt() ?: return@intercept originalResult
                    val settings = configuration.snapshot
                    if (!settings.enabled || !settings.handleSwipeUpToMiniEnabled) {
                        return@intercept originalResult
                    }
                    val actionFlag = (chain.getArg(1) as? Number)?.toInt() ?: return@intercept originalResult
                    val ordinary =
                        try {
                            chain.thisObject?.let(access::isOrdinaryFlexibleTask) == true
                        } catch (exception: ReflectiveOperationException) {
                            logFailure("HANDLE_SWIPE_UP_STATE_REFLECTION_FAILED", exception)
                            false
                        } catch (exception: RuntimeException) {
                            logFailure("HANDLE_SWIPE_UP_STATE_FAILED", exception)
                            false
                        }
                    val mappedMode =
                        HandleSwipeUpModeRemapper.remap(
                            enabled = true,
                            actionFlag = actionFlag,
                            originalMode = originalMode,
                            flexibleState = if (ordinary) ORDINARY_FLEXIBLE_STATE else UNKNOWN_STATE,
                        )
                    if (mappedMode != originalMode && remapLogged.compareAndSet(false, true)) {
                        module.log(Log.INFO, TAG, "HANDLE_SWIPE_UP_MODE_REMAPPED")
                    }
                    mappedMode
                }
            module.log(Log.INFO, TAG, "HANDLE_SWIPE_UP_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "HANDLE_SWIPE_UP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "HANDLE_SWIPE_UP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.log(Log.WARN, TAG, code, throwable)
    }

    private class ColorOsHandleSwipeAccess(
        scaleManagerClass: Class<*>,
        controllerClass: Class<*>,
        taskClass: Class<*>,
    ) {
        private val taskField = scaleManagerClass.requiredSwipeField("mTask")
        private val controllerField = scaleManagerClass.requiredSwipeField("mFlexibleTaskController")
        private val isTaskInFlexibleState =
            controllerClass.requiredSwipeMethod(
                name = "isTaskInFlexibleState",
                parameterTypes =
                    arrayOf(
                        taskClass,
                        Int::class.javaPrimitiveType!!,
                    ),
            )

        fun isOrdinaryFlexibleTask(scaleManager: Any): Boolean {
            val task = taskField.get(scaleManager) ?: return false
            val controller = controllerField.get(scaleManager) ?: return false
            return isTaskInFlexibleState.invokeSwipe(controller, task, ORDINARY_FLEXIBLE_STATE) == true
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val SCALE_MANAGER_CLASS = "com.android.server.wm.FlexibleTaskScaleManager"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val ORDINARY_FLEXIBLE_STATE = 1
        const val UNKNOWN_STATE = -1
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}

private fun Class<*>.requiredSwipeField(name: String): Field {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException(name)
}

private fun Class<*>.requiredSwipeMethod(name: String, parameterTypes: Array<Class<*>>): Method {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            current = current.superclass
        }
    }
    throw NoSuchMethodException(name)
}

private fun Method.invokeSwipe(instance: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(instance, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
