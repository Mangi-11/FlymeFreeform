package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.mangi.flymefreeform.gesture.OutsideTapGestureEngine
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor

/** 扩展 ColorOS 自有全局指针监听，只处理已验证的普通小窗窗外短点击。 */
@SuppressLint("PrivateApi")
internal class OutsideTapCloseHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val listenerClass = classLoader.loadClass(TOUCH_LISTENER_CLASS)
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val taskClass = classLoader.loadClass(TASK_CLASS)
            val displayContentClass = classLoader.loadClass(DISPLAY_CONTENT_CLASS)
            val windowStateClass = classLoader.loadClass(WINDOW_STATE_CLASS)
            val captionClass = classLoader.loadClass(FLEXIBLE_CAPTION_VIEW_CLASS)
            val onPointerEvent =
                listenerClass.getDeclaredMethod("onPointerEvent", MotionEvent::class.java)
            val updateTouchableRegion =
                captionClass.getDeclaredMethod("updateTouchableRegion", Region::class.java)
            val access =
                ColorOsOutsideTapAccess(
                    listenerClass = listenerClass,
                    controllerClass = controllerClass,
                    taskClass = taskClass,
                    displayContentClass = displayContentClass,
                    windowStateClass = windowStateClass,
                    captionClass = captionClass,
                    onFailure = ::logFailure,
                    environmentAllowed = environment::isModuleAllowed,
                )
            module
                .hook(updateTouchableRegion)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.outside_tap_input_region")
                .intercept { chain ->
                    val caption = chain.thisObject ?: return@intercept chain.proceed()
                    val originalRegion = chain.getArg(0) as? Region ?: return@intercept chain.proceed()
                    val protectedRegion =
                        try {
                            access.buildProtectedRegion(caption, originalRegion, configuration.snapshot)
                        } catch (exception: ReflectiveOperationException) {
                            access.clearProtection(caption)
                            logFailure("OUTSIDE_TAP_REGION_REFLECTION_FAILED", exception)
                            null
                        } catch (exception: RuntimeException) {
                            access.clearProtection(caption)
                            logFailure("OUTSIDE_TAP_REGION_FAILED", exception)
                            null
                        }
                    if (protectedRegion == null) {
                        return@intercept chain.proceed()
                    }
                    val result = chain.proceed(arrayOf(protectedRegion.region))
                    access.markProtected(caption, protectedRegion.task)
                    result
                }
            configuration.observe(access::onConfigurationChanged)
            environment.observe { access.onConfigurationChanged(configuration.snapshot) }
            module
                .hook(onPointerEvent)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.outside_tap_close")
                .intercept { chain ->
                    val listener = chain.thisObject
                    val event = chain.getArg(0) as? MotionEvent
                    val closeCandidate =
                        if (listener != null && event != null) {
                            try {
                                access.beforeOriginal(listener, event, configuration.snapshot)
                            } catch (exception: ReflectiveOperationException) {
                                access.interrupt(listener)
                                logFailure("OUTSIDE_TAP_RUNTIME_REFLECTION_FAILED", exception)
                                null
                            } catch (exception: RuntimeException) {
                                access.interrupt(listener)
                                logFailure("OUTSIDE_TAP_RUNTIME_FAILED", exception)
                                null
                            }
                        } else {
                            null
                        }
                    val result = chain.proceed()
                    if (listener != null && closeCandidate != null) {
                        try {
                            access.closeIfStillValid(listener, closeCandidate)
                        } catch (exception: ReflectiveOperationException) {
                            logFailure("OUTSIDE_TAP_CLOSE_REFLECTION_FAILED", exception)
                        } catch (exception: RuntimeException) {
                            logFailure("OUTSIDE_TAP_CLOSE_FAILED", exception)
                        }
                    }
                    result
                }
            module.log(Log.INFO, TAG, "OUTSIDE_TAP_INPUT_REGION_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "OUTSIDE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "OUTSIDE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        module.log(Log.WARN, TAG, code, throwable)
    }

    private class ColorOsOutsideTapAccess(
        listenerClass: Class<*>,
        controllerClass: Class<*>,
        taskClass: Class<*>,
        displayContentClass: Class<*>,
        windowStateClass: Class<*>,
        captionClass: Class<*>,
        private val onFailure: (String, Throwable) -> Unit,
        private val environmentAllowed: () -> Boolean,
    ) {
        private val controllerField = listenerClass.requiredField("this$0")
        private val contextField = controllerClass.requiredField("mContext")
        private val flexibleTasksField = controllerClass.requiredField("mFlexibleTasks")
        private val captionTaskField = captionClass.requiredField("mTask")
        private val captionControllerField = captionClass.requiredField("mFlexibleTaskController")
        private val inputMethodWindowField = displayContentClass.requiredField("mInputMethodWindow")
        private val updateCaptionTouchRegion = captionClass.requiredMethod("updateTouchRegion", 0)
        private val getTopZoomTask = controllerClass.requiredMethod("getTopZoomTask", 0)
        private val isCanRespondEvent = controllerClass.requiredMethod("isCanRespondEvent", 0)
        private val isTaskInFlexibleState =
            controllerClass.requiredMethod("isTaskInFlexibleState", 2)
        private val getVisibleBounds =
            controllerClass.requiredMethod(
                name = "getFlexibleTaskVisibleBounds",
                parameterTypes = arrayOf(taskClass),
            )
        private val hasTouchableTask =
            controllerClass.requiredMethod("hasFlexibleTaskInTouchableRegion", 2)
        private val hasMenuShowing = controllerClass.requiredMethod("hasFlexibleTaskMenuShow", 0)
        private val updateTapExcludeRegion =
            controllerClass.requiredMethod(
                name = "updateWindowTapExcludeRegion",
                parameterTypes = arrayOf(displayContentClass, Region::class.java),
            )
        private val isIgnoreExpandRegion =
            controllerClass.requiredMethod("isIgnoreExpandRegion", 1)
        private val exitFlexibleTask =
            controllerClass.requiredMethod(
                name = "exitFlexibleTask",
                parameterTypes =
                    arrayOf(
                        taskClass,
                        Boolean::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    ),
            )
        private val getDisplayContent = taskClass.requiredMethod("getDisplayContent", 0)
        private val getTaskBounds = taskClass.requiredMethod("getBounds", 0)
        private val getDisplayBounds = displayContentClass.requiredMethod("getBounds", 0)
        private val isWindowVisible = windowStateClass.requiredMethod("isVisible", 0)
        private val getTouchableRegion =
            windowStateClass.requiredMethod(
                name = "getTouchableRegion",
                parameterTypes = arrayOf(Region::class.java),
            )
        private val engines = IdentityHashMap<Any, OutsideTapGestureEngine>()
        private val captions = WeakHashMap<Any, Unit>()
        private val protectedTasks = WeakHashMap<Any, Unit>()

        fun onConfigurationChanged(
            settings: io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot,
        ) {
            interruptAll()
            val knownCaptions = synchronized(captions) { captions.keys.toList() }
            knownCaptions.forEach { caption ->
                try {
                    updateCaptionTouchRegion.invokeUnwrapped(caption)
                } catch (exception: ReflectiveOperationException) {
                    clearProtection(caption)
                    onFailure("OUTSIDE_TAP_REGION_REFRESH_REFLECTION_FAILED", exception)
                } catch (exception: RuntimeException) {
                    clearProtection(caption)
                    onFailure("OUTSIDE_TAP_REGION_REFRESH_FAILED", exception)
                }
            }
            if (!environmentAllowed() || !settings.enabled || settings.outsideTapCloseMode == OutsideTapCloseMode.Disabled) {
                synchronized(protectedTasks) { protectedTasks.clear() }
            }
        }

        fun buildProtectedRegion(
            caption: Any,
            originalRegion: Region,
            settings: io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot,
        ): ProtectedTouchableRegion? {
            synchronized(captions) { captions[caption] = Unit }
            val task = captionTaskField.get(caption) ?: return clearProtection(caption)
            val controller = captionControllerField.get(caption) ?: return clearProtection(caption)
            val mode =
                if (settings.enabled && environmentAllowed()) settings.outsideTapCloseMode
                else OutsideTapCloseMode.Disabled
            if (
                mode == OutsideTapCloseMode.Disabled ||
                    !isOrdinaryZoom(controller, task) ||
                    hasMenuShowing.invokeUnwrapped(controller) == true
            ) {
                return clearProtection(caption)
            }
            val displayContent = getDisplayContent.invokeUnwrapped(task) ?: return clearProtection(caption)
            if (isIgnoreExpandRegion.invokeUnwrapped(controller, displayContent) == true) {
                return clearProtection(caption)
            }
            val captionView = caption as? View ?: return clearProtection(caption)
            val localWidth = captionView.width
            val localHeight = captionView.height
            val taskBounds = getTaskBounds.invokeUnwrapped(task) as? Rect ?: return clearProtection(caption)
            val visibleBounds = getVisibleBounds.invokeUnwrapped(controller, task) as? Rect
                ?: return clearProtection(caption)
            val displayBounds = getDisplayBounds.invokeUnwrapped(displayContent) as? Rect
                ?: return clearProtection(caption)
            if (
                localWidth <= 0 ||
                    localHeight <= 0 ||
                    taskBounds.isEmpty ||
                    visibleBounds.isEmpty ||
                    displayBounds.isEmpty
            ) {
                return clearProtection(caption)
            }
            val scaleX = visibleBounds.width().toFloat() / localWidth
            val scaleY = visibleBounds.height().toFloat() / localHeight
            if (
                !scaleX.isFinite() ||
                    !scaleY.isFinite() ||
                    scaleX <= 0f ||
                    scaleY <= 0f ||
                    kotlin.math.abs(scaleX - scaleY) > SCALE_TOLERANCE
            ) {
                return clearProtection(caption)
            }

            val outsideOnScreen = Region(displayBounds)
            outsideOnScreen.op(visibleBounds, Region.Op.DIFFERENCE)
            allFlexibleTaskBounds(controller).forEach { bounds ->
                outsideOnScreen.op(bounds, Region.Op.DIFFERENCE)
            }
            val context = contextField.get(controller) as? Context ?: return clearProtection(caption)
            val statusBarBottom = displayBounds.top + statusBarHeight(context)
            if (statusBarBottom > displayBounds.top) {
                outsideOnScreen.op(
                    Rect(displayBounds.left, displayBounds.top, displayBounds.right, statusBarBottom),
                    Region.Op.DIFFERENCE,
                )
            }
            val excludedRegion =
                updateTapExcludeRegion.invokeUnwrapped(controller, displayContent, null) as? Region
            if (excludedRegion != null && !excludedRegion.isEmpty) {
                outsideOnScreen.op(excludedRegion, Region.Op.DIFFERENCE)
            }
            visibleImeRegion(displayContent)?.let { imeRegion ->
                outsideOnScreen.op(imeRegion, Region.Op.DIFFERENCE)
            }
            if (outsideOnScreen.isEmpty) return clearProtection(caption)

            val localOutside =
                screenToCaptionRegion(
                    screenRegion = outsideOnScreen,
                    visibleBounds = visibleBounds,
                    scaleX = scaleX,
                    scaleY = scaleY,
                )
            if (localOutside.isEmpty) return clearProtection(caption)
            val protectedRegion = Region(originalRegion)
            protectedRegion.op(localOutside, Region.Op.UNION)
            return ProtectedTouchableRegion(task, protectedRegion)
        }

        fun markProtected(caption: Any, task: Any) {
            synchronized(captions) { captions[caption] = Unit }
            synchronized(protectedTasks) { protectedTasks[task] = Unit }
        }

        fun clearProtection(caption: Any): ProtectedTouchableRegion? {
            val task = captionTaskField.get(caption)
            if (task != null) synchronized(protectedTasks) { protectedTasks.remove(task) }
            return null
        }

        private fun isProtected(task: Any): Boolean =
            synchronized(protectedTasks) { protectedTasks.containsKey(task) }

        private fun allFlexibleTaskBounds(controller: Any): List<Rect> {
            val tasks = flexibleTasksField.get(controller) ?: return emptyList()
            val taskSnapshot =
                synchronized(tasks) {
                    (tasks as? Iterable<*>)?.filterNotNull()?.toList().orEmpty()
                }
            return taskSnapshot.mapNotNull { flexibleTask ->
                (getVisibleBounds.invokeUnwrapped(controller, flexibleTask) as? Rect)
                    ?.takeUnless { it.isEmpty }
                    ?.let(::Rect)
            }
        }

        private fun visibleImeRegion(displayContent: Any): Region? {
            val imeWindow = inputMethodWindowField.get(displayContent) ?: return null
            if (isWindowVisible.invokeUnwrapped(imeWindow) != true) return null
            return Region().also { getTouchableRegion.invokeUnwrapped(imeWindow, it) }
        }

        private fun screenToCaptionRegion(
            screenRegion: Region,
            visibleBounds: Rect,
            scaleX: Float,
            scaleY: Float,
        ): Region {
            val result = Region()
            val iterator = RegionIterator(screenRegion)
            val screenRect = Rect()
            while (iterator.next(screenRect)) {
                val localRect =
                    Rect(
                        floor((screenRect.left - visibleBounds.left) / scaleX).toInt(),
                        floor((screenRect.top - visibleBounds.top) / scaleY).toInt(),
                        ceil((screenRect.right - visibleBounds.left) / scaleX).toInt(),
                        ceil((screenRect.bottom - visibleBounds.top) / scaleY).toInt(),
                    )
                if (!localRect.isEmpty) result.op(localRect, Region.Op.UNION)
            }
            return result
        }

        fun beforeOriginal(
            listener: Any,
            event: MotionEvent,
            settings: io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot,
        ): Any? =
            synchronized(engines) {
                val engine = engines.getOrPut(listener) { OutsideTapGestureEngine() }
                val mode =
                    if (settings.enabled && environmentAllowed()) settings.outsideTapCloseMode
                    else OutsideTapCloseMode.Disabled
                engine.updateMode(mode)
                if (mode == OutsideTapCloseMode.Disabled) return@synchronized null
                val controller = controllerField.get(listener) ?: run {
                    engine.interrupt()
                    return@synchronized null
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        beginOutsideTap(engine, controller, event, mode)
                    MotionEvent.ACTION_MOVE ->
                        updateOutsideTap(engine, controller, event)
                    MotionEvent.ACTION_UP ->
                        finishOutsideTap(engine, controller, event)
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_CANCEL,
                    -> {
                        engine.interrupt()
                        null
                    }
                    else -> null
                }
            }

        fun interrupt(listener: Any) {
            synchronized(engines) { engines[listener]?.interrupt() }
        }

        fun interruptAll() {
            synchronized(engines) { engines.values.forEach(OutsideTapGestureEngine::interrupt) }
        }

        fun closeIfStillValid(listener: Any, task: Any) {
            if (!environmentAllowed()) return
            val controller = controllerField.get(listener) ?: return
            if (getTopZoomTask.invokeUnwrapped(controller) !== task || !isOrdinaryZoom(controller, task)) {
                return
            }
            exitFlexibleTask.invokeUnwrapped(controller, task, true, 0, 0)
        }

        private fun beginOutsideTap(
            engine: OutsideTapGestureEngine,
            controller: Any,
            event: MotionEvent,
            mode: OutsideTapCloseMode,
        ): Any? {
            if (
                mode == OutsideTapCloseMode.Disabled ||
                    isCanRespondEvent.invokeUnwrapped(controller) != true ||
                    event.pointerCount != 1 ||
                    event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER ||
                    !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
            ) {
                engine.interrupt()
                return null
            }
            val task = getTopZoomTask.invokeUnwrapped(controller) ?: run {
                engine.interrupt()
                return null
            }
            if (!isOrdinaryZoom(controller, task) || !isOutsideEligibleRegion(controller, task, event)) {
                engine.interrupt()
                return null
            }
            // 只有标题输入层已从 DOWN 起接管窗外区域时才启用关闭判定。
            engine.begin(
                task = task,
                pointerId = event.getPointerId(0),
                x = event.rawX,
                y = event.rawY,
                eventTime = event.eventTime,
                captured = isProtected(task),
            )
            return null
        }

        private fun updateOutsideTap(
            engine: OutsideTapGestureEngine,
            controller: Any,
            event: MotionEvent,
        ): Any? {
            val task = getTopZoomTask.invokeUnwrapped(controller) ?: run {
                engine.interrupt()
                return null
            }
            if (!isOrdinaryZoom(controller, task)) {
                engine.interrupt()
                return null
            }
            val pointerId = event.getPointerId(0)
            engine.move(
                task = task,
                pointerId = pointerId,
                pointerCount = event.pointerCount,
                x = event.getRawX(0),
                y = event.getRawY(0),
                touchSlop = viewConfiguration(controller).scaledTouchSlop.toFloat(),
            )
            return null
        }

        private fun finishOutsideTap(
            engine: OutsideTapGestureEngine,
            controller: Any,
            event: MotionEvent,
        ): Any? {
            if (event.pointerCount == 0) {
                engine.interrupt()
                return null
            }
            val task = getTopZoomTask.invokeUnwrapped(controller) ?: run {
                engine.interrupt()
                return null
            }
            if (!isOrdinaryZoom(controller, task)) {
                engine.interrupt()
                return null
            }
            val configuration = viewConfiguration(controller)
            val pointerIndex = event.actionIndex
            val shouldClose =
                engine.finish(
                    task = task,
                    pointerId = event.getPointerId(pointerIndex),
                    x = event.getRawX(pointerIndex),
                    y = event.getRawY(pointerIndex),
                    eventTime = event.eventTime,
                    longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong(),
                    touchSlop = configuration.scaledTouchSlop.toFloat(),
                    doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong(),
                    doubleTapSlop = configuration.scaledDoubleTapSlop.toFloat(),
                )
            return task.takeIf { shouldClose }
        }

        private fun isOrdinaryZoom(controller: Any, task: Any): Boolean =
            isTaskInFlexibleState.invokeUnwrapped(controller, task, ORDINARY_ZOOM_STATE) == true

        private fun isOutsideEligibleRegion(controller: Any, task: Any, event: MotionEvent): Boolean {
            val pointX = event.rawX.toInt()
            val pointY = event.rawY.toInt()
            val context = contextField.get(controller) as? Context ?: return false
            if (pointY < statusBarHeight(context)) return false
            val displayContent = getDisplayContent.invokeUnwrapped(task) ?: return false
            if (isIgnoreExpandRegion.invokeUnwrapped(controller, displayContent) == true) return false
            if (hasMenuShowing.invokeUnwrapped(controller) == true) return false
            if (isInsideVisibleIme(displayContent, pointX, pointY)) return false
            val excludedRegion =
                updateTapExcludeRegion.invokeUnwrapped(controller, displayContent, null) as? Region
            if (excludedRegion?.contains(pointX, pointY) == true) return false
            if (hasTouchableTask.invokeUnwrapped(controller, pointX, pointY) == true) return false
            val visibleBounds = getVisibleBounds.invokeUnwrapped(controller, task) as? Rect ?: return false
            return !visibleBounds.isEmpty && !visibleBounds.contains(pointX, pointY)
        }

        private fun isInsideVisibleIme(displayContent: Any, x: Int, y: Int): Boolean {
            val imeWindow = inputMethodWindowField.get(displayContent) ?: return false
            if (isWindowVisible.invokeUnwrapped(imeWindow) != true) return false
            val touchRegion = Region()
            getTouchableRegion.invokeUnwrapped(imeWindow, touchRegion)
            return touchRegion.contains(x, y)
        }

        private fun viewConfiguration(controller: Any): ViewConfiguration {
            val context = contextField.get(controller) as? Context
                ?: throw IllegalStateException("Flexible task context unavailable")
            return ViewConfiguration.get(context)
        }

        private fun statusBarHeight(context: Context): Int {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val TOUCH_LISTENER_CLASS =
            "com.android.server.wm.FlexibleTaskController\$TouchListener"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val DISPLAY_CONTENT_CLASS = "com.android.server.wm.DisplayContent"
        const val WINDOW_STATE_CLASS = "com.android.server.wm.WindowState"
        const val FLEXIBLE_CAPTION_VIEW_CLASS = "com.android.server.wm.FlexibleCaptionView"
        const val ORDINARY_ZOOM_STATE = 1
        const val SCALE_TOLERANCE = 0.02f
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}

private data class ProtectedTouchableRegion(
    val task: Any,
    val region: Region,
)

private fun Class<*>.requiredField(name: String): Field {
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

private fun Class<*>.requiredMethod(name: String, parameterCount: Int): Method {
    var current: Class<*>? = this
    while (current != null) {
        current.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        }?.let { return it.apply { isAccessible = true } }
        current = current.superclass
    }
    throw NoSuchMethodException("$name/$parameterCount")
}

private fun Class<*>.requiredMethod(name: String, parameterTypes: Array<Class<*>>): Method {
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

private fun Method.invokeUnwrapped(instance: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(instance, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
