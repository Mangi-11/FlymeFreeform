package io.github.mangi.flymefreeform.platform.common

import android.content.Context

/** system_server 中窗口与任务栈的访问接缝；具体字段名由平台实现负责。 */
internal interface SystemWindowAccess {
    val context: Context

    fun windowManagerService(): Any

    fun focusedWindowPackage(): String?

    fun topRootTaskPackage(): String?
}