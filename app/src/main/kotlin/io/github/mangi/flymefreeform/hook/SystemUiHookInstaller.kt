package io.github.mangi.flymefreeform.hook

import android.content.Context
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

internal class SystemUiHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bindings =
        Collections.synchronizedMap(WeakHashMap<Any, DetectorBinding>())
    private var environmentState: GestureEnvironmentState? = null

    fun install(classLoader: ClassLoader) {
        try {
            val handlerClass = classLoader.loadClass(EDGE_HANDLER_CLASS)
            val detectorClass = classLoader.loadClass(SIDE_DETECTOR_CLASS)
            val motionMethod =
                detectorClass.getDeclaredMethod("onMotionEventImpl", MotionEvent::class.java)

            handlerClass.declaredConstructors.forEach { constructor ->
                module
                    .hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.systemui.edge_owner")
                    .intercept { chain ->
                        val result = chain.proceed()
                        bindDetector(chain.thisObject)
                        result
                    }
            }
            handlerClass.declaredMethods
                .firstOrNull { method ->
                    method.name == "onInputEvent\$1" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0] == InputEvent::class.java
                }
                ?.let { inputMethod ->
                    module
                        .hook(inputMethod)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("flymefreeform.systemui.edge_owner_refresh")
                        .intercept { chain ->
                            bindDetector(chain.thisObject)
                            chain.proceed()
                        }
                }
            module
                .hook(motionMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.corner_guard")
                .intercept { chain ->
                    val detector = chain.thisObject
                    val event = chain.getArg(0) as MotionEvent
                    val binding = bindings[detector]
                    if (binding == null) return@intercept chain.proceed()
                    when (binding.guard.onMotionEvent(event, configuration.snapshot)) {
                        InputDisposition.PassThrough -> chain.proceed()
                        InputDisposition.Suppress -> null
                        InputDisposition.PilferAndSuppress -> {
                            if (pilfer(binding.handler)) proceedAsCancel(chain, event)
                            else {
                                binding.guard.abandon()
                                chain.proceed()
                            }
                        }
                    }
                }
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_GUARD_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun bindDetector(handler: Any?) {
        if (handler == null) return
        val context = findField(handler.javaClass, "mContext").get(handler) as? Context ?: return
        val eligibility = environmentState ?: GestureEnvironmentState(context).also { environmentState = it }
        val detector = findField(handler.javaClass, "mSideGestureDetector").get(handler) ?: return
        val metrics = context.resources.displayMetrics
        if (!bindings.containsKey(detector)) {
            bindings[detector] =
                DetectorBinding(
                    handler = handler,
                    guard =
                        CornerInputGuard(
                            touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
                            displaySize = {
                                metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
                            },
                            environmentAllowed = eligibility::isAllowed,
                        ),
                )
        }
    }

    private fun pilfer(handler: Any): Boolean =
        try {
            val monitor = findField(handler.javaClass, "mInputMonitor").get(handler) ?: return false
            monitor.javaClass.getMethod("pilferPointers").invoke(monitor)
            true
        } catch (exception: ReflectiveOperationException) {
            false
        }

    private fun proceedAsCancel(
        chain: XposedInterface.Chain,
        event: MotionEvent,
    ): Any? {
        val cancellation = MotionEvent.obtain(event)
        cancellation.action = MotionEvent.ACTION_CANCEL
        return try {
            chain.proceed(arrayOf<Any>(cancellation))
        } finally {
            cancellation.recycle()
        }
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private data class DetectorBinding(val handler: Any, val guard: CornerInputGuard)

    private companion object {
        const val TAG = "FlymeFreeform"
        const val EDGE_HANDLER_CLASS =
            "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler"
        const val SIDE_DETECTOR_CLASS =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector"
    }
}
