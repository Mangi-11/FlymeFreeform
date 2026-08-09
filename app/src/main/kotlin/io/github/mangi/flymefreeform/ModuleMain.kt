package io.github.mangi.flymefreeform

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.mangi.flymefreeform.hook.LauncherHookInstaller
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.hook.SystemServerHookInstaller
import io.github.mangi.flymefreeform.hook.SystemUiHookInstaller

/** libxposed API 102 入口；具体手势、窗口和厂商接口分别由对应职责实现。 */
class ModuleMain : XposedModule() {
    private var processName: String? = null
    private var configuration: ProcessConfiguration? = null
    private var hooksInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        if (!isExpectedProcess(param)) {
            log(Log.WARN, TAG, "MODULE_SKIPPED_UNEXPECTED_PROCESS")
            detach()
            return
        }

        val properties = getFrameworkProperties()
        if (properties and XposedInterface.PROP_CAP_REMOTE == 0L) {
            log(Log.WARN, TAG, "MODULE_CONFIG_REMOTE_UNAVAILABLE")
            detach()
            return
        }
        if (param.isSystemServer && properties and XposedInterface.PROP_CAP_SYSTEM == 0L) {
            log(Log.WARN, TAG, "MODULE_SYSTEM_CAPABILITY_UNAVAILABLE")
            detach()
            return
        }

        try {
            val processConfiguration =
                ProcessConfiguration(
                    log = { priority, code, throwable -> log(priority, TAG, code, throwable) },
                )
            processConfiguration.start(
                getRemotePreferences(io.github.mangi.flymefreeform.config.ModulePreferences.GROUP),
            )
            configuration = processConfiguration
        } catch (exception: UnsupportedOperationException) {
            disableForConfigurationFailure("MODULE_CONFIG_UNSUPPORTED", exception)
        } catch (exception: SecurityException) {
            disableForConfigurationFailure("MODULE_CONFIG_ACCESS_DENIED", exception)
        } catch (exception: IllegalStateException) {
            disableForConfigurationFailure("MODULE_CONFIG_NOT_READY", exception)
        } catch (exception: RuntimeException) {
            disableForConfigurationFailure("MODULE_CONFIG_FAILURE", exception)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val settings = configuration ?: return
        if (hooksInstalled) return
        hooksInstalled = true
        SystemServerHookInstaller(this, settings).install(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val settings = configuration ?: return
        if (hooksInstalled) return
        when {
            processName == PROCESS_SYSTEM_UI && param.packageName == PROCESS_SYSTEM_UI -> {
                hooksInstalled = true
                SystemUiHookInstaller(this, settings).install(param.classLoader)
            }

            processName == PROCESS_LAUNCHER && param.packageName == PROCESS_LAUNCHER -> {
                hooksInstalled = true
                LauncherHookInstaller(this, settings).install(param.classLoader)
            }
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.WARN, TAG, "HOT_RELOAD_REJECTED_RESTART_REQUIRED")
        return false
    }

    private fun isExpectedProcess(param: XposedModuleInterface.ModuleLoadedParam): Boolean =
        param.isSystemServer ||
            param.processName == PROCESS_SYSTEM_UI ||
            param.processName == PROCESS_LAUNCHER

    private fun disableForConfigurationFailure(code: String, exception: RuntimeException) {
        configuration = null
        log(Log.ERROR, TAG, code, exception)
        detach()
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val PROCESS_SYSTEM_UI = "com.android.systemui"
        const val PROCESS_LAUNCHER = "com.android.launcher"
    }
}
