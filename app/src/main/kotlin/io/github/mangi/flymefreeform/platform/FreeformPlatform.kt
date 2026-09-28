package io.github.mangi.flymefreeform.platform

import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.hook.ModuleEnvironmentState
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.platform.common.FreeformPlatformComponents

/** system_server 的引导锚点；按名字与参数个数定位，避免厂商签名漂移。 */
internal data class SystemServerAnchor(
    val className: String,
    val methodName: String,
    val parameterCount: Int,
)

/** 系统「游戏模式生效」信号：设置命名空间 + 键名 + 生效值。 */
internal data class GameModeSignal(
    val namespace: SettingsNamespace,
    val key: String,
    val activeValue: Int,
)

internal enum class SettingsNamespace { Global, System, Secure }

/** 各系统平台的自由窗能力描述；分派只发生在 PlatformRouting。 */
internal interface FreeformPlatform {
    val id: String

    /** 模块运行所需的框架作用域。 */
    val requiredScopes: Set<String>

    /** 桌面包名与进程名。 */
    val launcherProcessName: String

    val systemServerAnchor: SystemServerAnchor

    /** SystemUI 中承载角落热区窗口的 Application 类。 */
    val systemUiApplicationClassName: String

    /** 游戏模式信号；缺失的键不会误触发暂停。 */
    val gameModeSignal: GameModeSignal

    /** 桌面进程内的平台钩子。 */
    fun installLauncherHooks(
        module: XposedModule,
        configuration: ProcessConfiguration,
        classLoader: ClassLoader,
    )

    /** system_server 内平台额外的增强钩子（ColorOS 的窗外关闭与手柄上滑）。 */
    fun installSystemServerEnhancements(
        module: XposedModule,
        configuration: ProcessConfiguration,
        environment: ModuleEnvironmentState,
        classLoader: ClassLoader,
    )

    /** 由锚点对象构建协调器组件；锚点尚未就绪时返回 null。 */
    fun createComponents(
        anchor: Any,
        classLoader: ClassLoader,
        logger: (Int, String, Throwable?) -> Unit,
    ): FreeformPlatformComponents?
}