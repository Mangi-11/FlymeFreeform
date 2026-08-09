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

internal class LauncherHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val guards = Collections.synchronizedMap(WeakHashMap<Any, CornerInputGuard>())
    private var environmentState: GestureEnvironmentState? = null

    fun install(classLoader: ClassLoader) {
        try {
            val serviceClass = classLoader.loadClass(TOUCH_SERVICE_CLASS)
            val method = serviceClass.getDeclaredMethod("onInputEventInternal", InputEvent::class.java)
            val concreteServiceClass = classLoader.loadClass(CONCRETE_TOUCH_SERVICE_CLASS)
            findNoArgMethod(concreteServiceClass, "onCreate")
                .let { onCreate ->
                    module
                        .hook(onCreate)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("flymefreeform.launcher.owner_ready")
                        .intercept { chain ->
                            val result = chain.proceed()
                            chain.thisObject?.let { owner ->
                                if (!guards.containsKey(owner)) guards[owner] = createGuard(owner)
                            }
                            result
                        }
                }
            module
                .hook(method)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.launcher.corner_guard")
                .intercept { chain ->
                    val event = chain.getArg(0)
                    if (event !is MotionEvent) return@intercept chain.proceed()
                    val owner = chain.thisObject ?: return@intercept chain.proceed()
                    val guard = guards[owner] ?: return@intercept chain.proceed()
                    when (guard.onMotionEvent(event, configuration.snapshot)) {
                        InputDisposition.PassThrough -> chain.proceed()
                        InputDisposition.Suppress -> null
                        InputDisposition.PilferAndSuppress -> {
                            if (pilfer(owner)) proceedAsCancel(chain, event)
                            else {
                                guard.abandon()
                                chain.proceed()
                            }
                        }
                    }
                }
            module.log(Log.INFO, TAG, "LAUNCHER_CORNER_GUARD_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "LAUNCHER_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "LAUNCHER_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun createGuard(owner: Any): CornerInputGuard {
        val context = owner as? Context ?: findField(owner.javaClass, "mContext").get(owner) as Context
        val eligibility = environmentState ?: GestureEnvironmentState(context).also { environmentState = it }
        val metrics = context.resources.displayMetrics
        return CornerInputGuard(
            touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            displaySize = {
                metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
            },
            environmentAllowed = eligibility::isAllowed,
        )
    }

    private fun pilfer(owner: Any): Boolean =
        try {
            val monitor = findField(owner.javaClass, "mInputMonitorCompat").get(owner) ?: return false
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

    private fun findNoArgMethod(type: Class<*>, name: String): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == 0
            }?.let { return it }
            current = current.superclass
        }
        throw NoSuchMethodException(name)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val TOUCH_SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"
        const val CONCRETE_TOUCH_SERVICE_CLASS = "com.android.quickstep.TouchInteractionService"
    }
}
