package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.HandleDoubleTapGestureEngine
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.WeakHashMap

/** 观察原生标题层事件，不吞事件；原生 UP 清理拖动状态后再调用系统关闭接口。 */
@SuppressLint("PrivateApi")
internal class HandleDoubleTapCloseHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private val engines = WeakHashMap<View, HandleDoubleTapGestureEngine>()
    private var lastFailureAt = -10_000L

    fun install(loader: ClassLoader) {
        try {
            val captionClass = loader.loadClass("com.android.server.wm.FlexibleCaptionView")
            val access = Access(loader, captionClass)
            val dispatch = captionClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            module.hook(dispatch)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.handle_double_tap_close")
                .intercept { chain ->
                    val caption = chain.thisObject as? View ?: return@intercept chain.proceed()
                    val event = chain.getArg(0) as? MotionEvent ?: return@intercept chain.proceed()
                    val candidate = safely {
                        synchronized(engines) { beforeDispatch(access, caption, event) }
                    }
                    // 不替换 DOWN/UP，确保系统缩放管理器与拖动标记完整收尾。
                    val result = chain.proceed()
                    if (candidate != null) safely {
                        if (isEnabled() && access.task(caption) === candidate &&
                            access.isOrdinary(caption, candidate)
                        ) access.close(caption, candidate)
                    }
                    result
                }
            configuration.observe { reset() }
            environment.observe { reset() }
            module.log(Log.INFO, TAG, "HANDLE_DOUBLE_TAP_CLOSE_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "HANDLE_DOUBLE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "HANDLE_DOUBLE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun isEnabled(): Boolean =
        configuration.snapshot.handleDoubleTapCloseEnabled && environment.isModuleAllowed()

    private fun reset() = synchronized(engines) { engines.clear() }

    private fun beforeDispatch(access: Access, caption: View, event: MotionEvent): Any? {
        if (!isEnabled()) {
            engines.clear()
            return null
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            engines.keys.filter { it !== caption }.forEach { engines.remove(it) }
        }
        val engine = engines.getOrPut(caption) { HandleDoubleTapGestureEngine() }
        val task = access.task(caption)
        if (task == null || !caption.isAttachedToWindow || !access.isOrdinary(caption, task) ||
            access.shouldIgnore(caption, event) || event.pointerCount != 1 ||
            !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
            event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
        ) {
            engine.reset()
            return null
        }
        val config = ViewConfiguration.get(caption.context)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!access.isInHandle(caption, event)) {
                    engine.reset()
                    return null
                }
                engine.begin(
                    task, event.getPointerId(0), event.rawX, event.rawY, event.eventTime,
                    ViewConfiguration.getDoubleTapTimeout().toLong(),
                    config.scaledDoubleTapSlop.toFloat(),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                // 检查历史采样，不能让快速移出又返回的拖动被误判为点击。
                for (index in 0 until event.historySize) {
                    engine.move(
                        task, event.getPointerId(0),
                        event.getHistoricalX(0, index) + event.rawX - event.x,
                        event.getHistoricalY(0, index) + event.rawY - event.y,
                        config.scaledTouchSlop.toFloat(),
                    )
                }
                engine.move(task, event.getPointerId(0), event.rawX, event.rawY, config.scaledTouchSlop.toFloat())
            }
            MotionEvent.ACTION_UP -> {
                if (!access.isInHandle(caption, event)) {
                    engine.reset()
                    return null
                }
                val close = engine.finish(
                    task, event.getPointerId(0), event.rawX, event.rawY, event.eventTime,
                    config.scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong(),
                )
                if (close) return task
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> engine.reset()
        }
        return null
    }

    private fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (exception: ReflectiveOperationException) {
        failed(exception)
        null
    } catch (exception: RuntimeException) {
        failed(exception)
        null
    }

    private fun failed(exception: Exception) {
        reset()
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureAt < 10_000L) return
        lastFailureAt = now
        module.log(Log.WARN, TAG, "HANDLE_DOUBLE_TAP_FAILED", exception)
    }

    private class Access(loader: ClassLoader, captionClass: Class<*>) {
        private val controllerClass = loader.loadClass("com.android.server.wm.FlexibleTaskController")
        private val taskClass = loader.loadClass("com.android.server.wm.Task")
        private val regionClass = loader.loadClass("com.android.server.wm.FlexibleCaptionViewTouchRegionMgr")
        private val taskField = captionClass.getDeclaredField("mTask").apply { isAccessible = true }
        private val controllerField = captionClass.getDeclaredField("mFlexibleTaskController").apply { isAccessible = true }
        private val regionField = captionClass.getDeclaredField("mFlexibleCaptionViewRegionMgr").apply { isAccessible = true }
        private val bottomRegionField = regionClass.getDeclaredField("mBottomHandleButtonRegion").apply { isAccessible = true }
        private val regionLockField = regionClass.getDeclaredField("mTouchRegionLock").apply { isAccessible = true }
        private val bottomVisible = captionClass.getDeclaredMethod("isBottomHandleShouldVisible").apply { isAccessible = true }
        private val ignore = captionClass.getDeclaredMethod("shouldIgnoreTouchEvent", MotionEvent::class.java).apply { isAccessible = true }
        private val ordinary = controllerClass.getDeclaredMethod("isTaskInFlexibleState", taskClass, Int::class.javaPrimitiveType).apply { isAccessible = true }
        private val exit = controllerClass.getDeclaredMethod(
            "exitFlexibleTask", taskClass, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        fun task(caption: View): Any? = taskField.get(caption)

        fun isOrdinary(caption: View, task: Any): Boolean =
            ordinary.call(controllerField.get(caption), task, 1) == true

        fun shouldIgnore(caption: View, event: MotionEvent): Boolean = ignore.call(caption, event) != false

        fun isInHandle(caption: View, event: MotionEvent): Boolean {
            if (bottomVisible.call(caption) != true) return false
            val manager = regionField.get(caption) ?: return false
            val lock = regionLockField.get(manager) ?: return false
            // 8/10 同时包含整条底边缩放区，不能仅凭 actionFlag 判断横条命中。
            return synchronized(lock) {
                val region = bottomRegionField.get(manager) as? Rect
                region != null && !region.isEmpty && region.contains(event.x.toInt(), event.y.toInt())
            }
        }

        fun close(caption: View, task: Any) {
            exit.call(controllerField.get(caption), task, true, 0, 0)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}

private fun Method.call(instance: Any?, vararg args: Any?): Any? = try {
    invoke(instance, *args)
} catch (exception: InvocationTargetException) {
    throw exception.targetException
}
