package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformCoordinator
import java.util.concurrent.atomic.AtomicBoolean

internal class SystemServerHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)
    private val environment = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }

    fun install(classLoader: ClassLoader) {
        OutsideTapCloseHookInstaller(module, configuration, environment).install(classLoader)
        HandleSwipeUpHookInstaller(module, configuration, environment).install(classLoader)
        try {
            // 厂商初始化入口只能反射定位；缺失时保留系统启动流程。
            @SuppressLint("PrivateApi")
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val systemReady = controllerClass.getDeclaredMethod("systemReady", Boolean::class.javaPrimitiveType)
            module
                .hook(systemReady)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.flexible_ready")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject
                    if (controller != null && bound.compareAndSet(false, true)) {
                        ColorOsFreeformCoordinator(
                            controller = controller,
                            classLoader = classLoader,
                            configuration = configuration,
                            environmentState = environment,
                            logger = { priority, code, throwable ->
                                module.log(priority, TAG, code, throwable)
                            },
                        ).start()
                    }
                    result
                }
            module.log(Log.INFO, TAG, "SYSTEM_FREEFORM_READY_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
    }
}
