package io.github.mangi.flymefreeform.platform.common

import android.content.Context
import android.os.Handler

/** 平台为协调器提供的组件集合；由各平台在 system_server 引导钩子里按锚点构建。 */
internal class FreeformPlatformComponents(
    val context: Context,
    val windowAccess: SystemWindowAccess,
    val launcher: FreeformLauncher,
    val criticalPackages: Set<String>,
    val iconShaper: RadialIconShaper?,
    /** 「更多」面板桥接需要主线程 Handler 构造。 */
    val createMorePanel: (Handler) -> MorePanelDelegate,
)