package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import io.github.mangi.flymefreeform.platform.common.SystemWindowAccess
import io.github.mangi.flymefreeform.platform.common.findMethod
import io.github.mangi.flymefreeform.platform.common.readField

/** ColorOS 的引导锚点即自由窗控制器；窗口与任务栈经由 mAtms 访问。 */
internal class ColorOsSystemWindowAccess(
    private val controller: Any,
) : SystemWindowAccess {
    override val context: Context =
        controller.readField("mContext") as? Context
            ?: throw IllegalStateException("FREEFORM_CONTROLLER_CONTEXT_UNAVAILABLE")

    override fun windowManagerService(): Any {
        val atms = controller.readField("mAtms") ?: throw NoSuchFieldException("mAtms")
        return atms.readField("mWindowManager") ?: throw NoSuchFieldException("mWindowManager")
    }

    override fun focusedWindowPackage(): String? =
        try {
            val root = windowManagerService().readField("mRoot") ?: return null
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
            val atms = controller.readField("mAtms") ?: return null
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

/** 这些包在前台时不唤出手势增强。 */
internal val COLOROS_CRITICAL_PACKAGES: Set<String> =
    setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
    )