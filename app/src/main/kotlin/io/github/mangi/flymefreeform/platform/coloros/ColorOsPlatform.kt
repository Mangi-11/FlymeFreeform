package io.github.mangi.flymefreeform.platform.coloros

import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.hook.HandleSwipeUpHookInstaller
import io.github.mangi.flymefreeform.hook.LauncherHookInstaller
import io.github.mangi.flymefreeform.hook.ModuleEnvironmentState
import io.github.mangi.flymefreeform.hook.OutsideTapCloseHookInstaller
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.platform.FreeformPlatform
import io.github.mangi.flymefreeform.platform.GameModeSignal
import io.github.mangi.flymefreeform.platform.SettingsNamespace
import io.github.mangi.flymefreeform.platform.SystemServerAnchor
import io.github.mangi.flymefreeform.platform.common.FreeformPlatformComponents
import io.github.mangi.flymefreeform.platform.common.RadialIconShaper

/** ColorOS：沿用自由窗控制器作为锚点，保留窗外关闭、手柄上滑与侧边栏全部面板。 */
internal object ColorOsPlatform : FreeformPlatform {
    override val id: String = "coloros"

    override val requiredScopes: Set<String> =
        linkedSetOf(
            "system",
            "com.android.systemui",
            "com.android.launcher",
            "com.coloros.smartsidebar",
        )

    override val launcherProcessName: String = "com.android.launcher"

    override val systemServerAnchor: SystemServerAnchor =
        SystemServerAnchor(
            className = "com.android.server.wm.FlexibleTaskController",
            methodName = "systemReady",
            parameterCount = 1,
        )

    override val systemUiApplicationClassName: String = "com.android.systemui.SystemUIApplication"

    override val gameModeSignal: GameModeSignal =
        GameModeSignal(
            namespace = SettingsNamespace.Global,
            key = "debug_gamemode_value",
            activeValue = 1,
        )

    override fun installLauncherHooks(
        module: XposedModule,
        configuration: ProcessConfiguration,
        classLoader: ClassLoader,
    ) {
        LauncherHookInstaller(module, configuration).install(classLoader)
    }

    override fun installSystemServerEnhancements(
        module: XposedModule,
        configuration: ProcessConfiguration,
        environment: ModuleEnvironmentState,
        classLoader: ClassLoader,
    ) {
        OutsideTapCloseHookInstaller(module, configuration, environment).install(classLoader)
        HandleSwipeUpHookInstaller(module, configuration, environment).install(classLoader)
    }

    override fun createComponents(
        anchor: Any,
        classLoader: ClassLoader,
        logger: (Int, String, Throwable?) -> Unit,
    ): FreeformPlatformComponents? {
        val windowAccess = ColorOsSystemWindowAccess(anchor)
        val iconRenderer = ColorOsRadialIconRenderer(windowAccess.context.resources, logger)
        return FreeformPlatformComponents(
            context = windowAccess.context,
            windowAccess = windowAccess,
            launcher = ColorOsFreeformLauncher(windowAccess.context),
            criticalPackages = COLOROS_CRITICAL_PACKAGES,
            iconShaper = RadialIconShaper(iconRenderer::shapedIcon),
            createMorePanel = { handler ->
                ColorOsSidebarClient(windowAccess.context, handler, logger)
            },
        )
    }
}