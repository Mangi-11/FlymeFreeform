package io.github.mangi.flymefreeform.platform.common

import android.content.ComponentName

/** 平台相关的自由窗启动策略；失败必须显式返回，不得退化为全屏启动。 */
internal interface FreeformLauncher {
    fun launch(component: ComponentName): FreeformLaunchResult
}

internal sealed interface FreeformLaunchResult {
    data object Started : FreeformLaunchResult

    data object TargetUnavailable : FreeformLaunchResult

    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable,
    ) : FreeformLaunchResult
}