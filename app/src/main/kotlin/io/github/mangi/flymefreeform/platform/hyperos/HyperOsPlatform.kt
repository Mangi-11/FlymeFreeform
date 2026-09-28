package io.github.mangi.flymefreeform.platform.hyperos

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.hook.ModuleEnvironmentState
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.platform.FreeformPlatform
import io.github.mangi.flymefreeform.platform.GameModeSignal
import io.github.mangi.flymefreeform.platform.SettingsNamespace
import io.github.mangi.flymefreeform.platform.SystemServerAnchor
import io.github.mangi.flymefreeform.platform.common.BuiltInMorePanelDelegate
import io.github.mangi.flymefreeform.platform.common.FreeformPlatformComponents

/** HyperOS：以小窗管理器所在进程的 WMS.systemReady 为锚点，本版未接厂商面板与 ColorOS 专属增强。 */
internal object HyperOsPlatform : FreeformPlatform {
    override val id: String = "hyperos"

    override val requiredScopes: Set<String> =
        linkedSetOf(
            "system",
            "com.android.systemui",
            "com.miui.home",
        )

    override val launcherProcessName: String = "com.miui.home"

    override val systemServerAnchor: SystemServerAnchor =
        SystemServerAnchor(
            className = "com.android.server.wm.WindowManagerService",
            methodName = "systemReady",
            parameterCount = 0,
        )

    override val systemUiApplicationClassName: String =
        "com.android.systemui.application.impl.SystemUIApplicationImpl"

    /** 游戏加速生效时由系统写入的键；键缺失只会静默不生效，不会误暂停。 */
    override val gameModeSignal: GameModeSignal =
        GameModeSignal(
            namespace = SettingsNamespace.Secure,
            key = "gb_boosting",
            activeValue = 1,
        )

    override fun installLauncherHooks(
        module: XposedModule,
        configuration: ProcessConfiguration,
        classLoader: ClassLoader,
    ) {
        // 桌面侧的手势竞争尚未取证，本版只在桌面进程保持静默。
        module.log(Log.INFO, TAG, "LAUNCHER_ENHANCEMENT_SKIPPED")
    }

    override fun installSystemServerEnhancements(
        module: XposedModule,
        configuration: ProcessConfiguration,
        environment: ModuleEnvironmentState,
        classLoader: ClassLoader,
    ) = Unit

    override fun createComponents(
        anchor: Any,
        classLoader: ClassLoader,
        logger: (Int, String, Throwable?) -> Unit,
    ): FreeformPlatformComponents? {
        val windowAccess = HyperOsSystemWindowAccess(anchor)
        return FreeformPlatformComponents(
            context = windowAccess.context,
            windowAccess = windowAccess,
            launcher = HyperOsFreeformLauncher(windowAccess.context, logger),
            criticalPackages = HYPEROS_CRITICAL_PACKAGES,
            iconShaper = null,
            createMorePanel = { BuiltInMorePanelDelegate },
        )
    }

    /** 这些包在前台时不唤出手势增强。 */
    private val HYPEROS_CRITICAL_PACKAGES: Set<String> =
        setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
        )

    private const val TAG = "FlymeFreeform"
}