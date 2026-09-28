package io.github.mangi.flymefreeform.platform.hyperos

import android.content.Context
import io.github.mangi.flymefreeform.platform.common.SystemWindowAccess
import io.github.mangi.flymefreeform.platform.common.findMethod
import io.github.mangi.flymefreeform.platform.common.readField

/** HyperOS 的引导锚点是 WMS；窗口与任务栈从 WMS 与其 mAtmService 取。 */
internal class HyperOsSystemWindowAccess(
    private val windowManagerService: Any,
) : SystemWindowAccess {
    override val context: Context =
        windowManagerService.readField("mContext") as? Context
            ?: throw IllegalStateException("HYPEROS_WMS_CONTEXT_UNAVAILABLE")

    override fun windowManagerService(): Any = windowManagerService

    override fun focusedWindowPackage(): String? =
        try {
            val root = windowManagerService.readField("mRoot") ?: return null
            val display =
                root.javaClass.findMethod("getTopFocusedDisplayContent", 0).invoke(root) ?: return null
            val window = display.readField("mCurrentFocus") ?: return null
            window.javaClass.findMethod("getOwningPackage", 0).invoke(window) as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    override fun topRootTaskPackage(): String? =
        try {
            val atms = windowManagerService.readField("mAtmService") ?: return null
            val root = atms.readField("mRootWindowContainer") ?: return null
            val task = root.javaClass.findMethod("getTopDisplayFocusedRootTask", 0).invoke(root) ?: return null
            val activity =
                task.javaClass.findMethod("topRunningActivity", 0).invoke(task) ?: return null
            activity.readField("packageName") as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}