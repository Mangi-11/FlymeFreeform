package io.github.mangi.flymefreeform.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.FreeformPlatform
import io.github.mangi.flymefreeform.platform.PlatformRouting
import io.github.mangi.flymefreeform.platform.common.FreeformGestureCoordinator
import io.github.mangi.flymefreeform.platform.common.findMethod
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
        val platform = PlatformRouting.current(classLoader)
        platform.installSystemServerEnhancements(module, configuration, environment, classLoader)
        installCoordinatorHook(platform, classLoader)
    }

    private fun installCoordinatorHook(
        platform: FreeformPlatform,
        classLoader: ClassLoader,
    ) {
        try {
            val anchor = platform.systemServerAnchor
            val anchorMethod =
                classLoader
                    .loadClass(anchor.className)
                    .findMethod(anchor.methodName, anchor.parameterCount)
            module
                .hook(anchorMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.freeform_ready")
                .intercept { chain ->
                    val result = chain.proceed()
                    val anchorInstance = chain.thisObject
                    if (anchorInstance != null && bound.compareAndSet(false, true)) {
                        startCoordinator(platform, anchorInstance, classLoader)
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

    private fun startCoordinator(
        platform: FreeformPlatform,
        anchor: Any,
        classLoader: ClassLoader,
    ) {
        val logger: (Int, String, Throwable?) -> Unit = { priority, code, throwable ->
            module.log(priority, TAG, code, throwable)
        }
        val components = platform.createComponents(anchor, classLoader, logger)
        if (components == null) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_ANCHOR_NOT_READY", null)
            return
        }
        FreeformGestureCoordinator(
            components = components,
            classLoader = classLoader,
            configuration = configuration,
            environmentState = environment,
            logger = logger,
        ).start()
    }

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}