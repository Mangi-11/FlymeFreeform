package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import io.github.mangi.flymefreeform.apps.AppTarget
import io.github.mangi.flymefreeform.apps.createContextForUser
import io.github.mangi.flymefreeform.apps.identifier

/** 每次提交都重新校验组件，并仅携带 ColorOS 自由窗参数启动，不退化为普通全屏启动。 */
internal class ColorOsFreeformLauncher(
    private val context: Context,
) {
    fun launch(
        target: AppTarget,
        newInstance: Boolean = false,
        flexible: Boolean = false,
        reuseTaskId: Int? = null,
    ): FreeformLaunchResult =
        try {
            val launchContext = contextFor(target)
            if (!isLaunchable(launchContext, target.component)) {
                return FreeformLaunchResult.TargetUnavailable
            }
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .setComponent(target.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply {
                        // 复用已有 task 时不能带 LAUNCHER 类别：ColorOS 原生小窗入口用的是纯
                        // ACTION_MAIN，带上类别会让系统按"启动器启动"处理并作用到前台 task 上。
                        if (newInstance || reuseTaskId == null) addCategory(Intent.CATEGORY_LAUNCHER)
                        // 已存在 task 时只有 MULTIPLE_TASK 能再开一个实例；前台本来就是主界面时，
                        // 系统仍会把 intent 投递给现有实例，不会多开。
                        if (newInstance) addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    }
            val options = createLaunchOptions(flexible, reuseTaskId)
            FreeformLaunchResult.Started(startForUser(intent, options, target, launchContext))
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_SECURITY_REJECTED", exception)
        } catch (exception: ReflectiveOperationException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_USER_CONTEXT_FAILED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_START_FAILED", exception)
        }

    /**
     * 分身（MultiApp 用户）不能靠调用方所在用户启动，必须显式带上目标用户。
     * 返回实际走通的路径，供日志确认真正生效的是哪一条。
     */
    private fun startForUser(
        intent: Intent,
        options: Bundle,
        target: AppTarget,
        launchContext: Context,
    ): String {
        if (target.userId == Process.myUserHandle().identifier) {
            launchContext.startActivity(intent, options)
            return LAUNCH_ROUTE_MAIN
        }
        val startActivityAsUser =
            Context::class.java.methods.firstOrNull { method ->
                method.name == START_ACTIVITY_AS_USER &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Intent::class.java, Bundle::class.java, UserHandle::class.java),
                    )
            }
        if (startActivityAsUser != null) {
            startActivityAsUser.invoke(context, intent, options, target.user)
            return LAUNCH_ROUTE_AS_USER
        }
        // 兜底：按用户创建的 Context 自身也以该用户身份发起启动。
        launchContext.startActivity(intent, options)
        return LAUNCH_ROUTE_USER_CONTEXT
    }

    private fun contextFor(target: AppTarget): Context =
        context.createContextForUser(target.userId)

    private fun isLaunchable(context: Context, component: ComponentName): Boolean =
        try {
            context.packageManager
                .getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                .let { info -> info.enabled && info.applicationInfo.enabled && info.exported }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun createLaunchOptions(
        flexible: Boolean,
        reuseTaskId: Int?,
    ): Bundle =
        Bundle().apply {
            putInt(ZOOM_FLAGS_KEY, if (flexible) FLEXIBLE_ZOOM_LAUNCH_FLAG else ZOOM_LAUNCH_FLAG)
            putInt(WINDOWING_MODE_KEY, FLEXIBLE_WINDOWING_MODE)
            createPlatformOptions(reuseTaskId)?.let(::putAll)
            // 必须最后合并：平台 options 里也带同名 key（内容更少），先放会被 putAll 覆盖掉，
            // 结果就是"第一次能开小窗、复用已有 task 时参数丢失、开不出来"。
            if (flexible) putBundle(ACTIVITY_EXTRA_KEY, createFlexibleExtras(getBundle(ACTIVITY_EXTRA_KEY)))
        }

    /**
     * ColorOS 小窗启动协议：`androidx.activity.extra` 里的 `ZoomLaunchFlag` 决定这次启动是否按
     * 小窗处理，`ZoomCallPkg` 记录发起方，其余键控制标题栏、圆角、复用与转分屏等行为。
     * 真机抓包来自智能侧边栏，缺了它们时系统只会把已有 task 提到前台而不会变成小窗。
     */
    private fun createFlexibleExtras(platformExtras: Bundle?): Bundle =
        Bundle(platformExtras ?: Bundle()).apply {
            putInt(ZOOM_LAUNCH_FLAG_KEY, FLEXIBLE_ZOOM_LAUNCH_FLAG)
            putString(ZOOM_CALL_PACKAGE_KEY, ZOOM_CALL_PACKAGE)
            putBoolean(EXTRA_ADJUST_INPUT_METHOD, true)
            putBoolean(EXTRA_HAS_CAPTION, true)
            putInt(EXTRA_LAUNCH_CORNER_RADIUS, LAUNCH_CORNER_RADIUS)
            putBoolean(EXTRA_MAINTAIN_TASK_STATE, true)
            putBoolean(EXTRA_CHANGE_TO_SPLIT, true)
            putBoolean(EXTRA_FOCUS_CHANGE_WITH_NON_FLEXIBLE, true)
            putInt(EXTRA_LAUNCH_SCENARIO, LAUNCH_SCENARIO)
            putBoolean(EXTRA_RESIZE_MODE, true)
            putBoolean(EXTRA_RESIZE_FOR_ORIENTATION_CHANGE, true)
            putInt(EXTRA_SOURCE_FLEXIBLE_TASK_ID, NO_SOURCE_TASK)
        }

    /**
     * 隐藏 setter 只是补强；ColorOS 的两个 Bundle 参数才是不可缺少的启动协议。
     * `reuseTaskId` 不为空时把启动锁进那个已存在的 task，避免系统又新建一个实例。
     */
    @SuppressLint("BlockedPrivateApi")
    private fun createPlatformOptions(reuseTaskId: Int?): Bundle? =
        try {
            ActivityOptions.makeBasic().let { options ->
                ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(options, FLEXIBLE_WINDOWING_MODE)
                if (reuseTaskId != null) {
                    ActivityOptions::class.java
                        .getDeclaredMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                        .also { it.isAccessible = true }
                        .invoke(options, reuseTaskId)
                }
                options.toBundle()
            }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private companion object {
        const val FLEXIBLE_WINDOWING_MODE = 100
        const val ZOOM_LAUNCH_FLAG = 4
        const val FLEXIBLE_ZOOM_LAUNCH_FLAG = 31
        const val LAUNCH_CORNER_RADIUS = 70
        const val LAUNCH_SCENARIO = 1
        const val NO_SOURCE_TASK = -1
        const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
        const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"
        const val ACTIVITY_EXTRA_KEY = "androidx.activity.extra"
        const val ZOOM_LAUNCH_FLAG_KEY = "ZoomLaunchFlag"
        const val ZOOM_CALL_PACKAGE_KEY = "ZoomCallPkg"
        const val EXTRA_ADJUST_INPUT_METHOD = "androidx.activity.AdjustInputMethod"
        const val EXTRA_HAS_CAPTION = "androidx.activity.HasCaption"
        const val EXTRA_LAUNCH_CORNER_RADIUS = "androidx.activity.LaunchCornerRadius"
        const val EXTRA_MAINTAIN_TASK_STATE = "androidx.activity.MaintainTaskState"
        const val EXTRA_CHANGE_TO_SPLIT = "androidx.activity.ChangeToSplit"
        const val EXTRA_FOCUS_CHANGE_WITH_NON_FLEXIBLE = "androidx.activity.FocusChangeWithNonFlexible"
        const val EXTRA_LAUNCH_SCENARIO = "androidx.activity.LaunchScenario"
        const val EXTRA_RESIZE_MODE = "androidx.flexible.ResizeMode"
        const val EXTRA_RESIZE_FOR_ORIENTATION_CHANGE = "androidx.activity.ResizeForOrientationChange"
        const val EXTRA_SOURCE_FLEXIBLE_TASK_ID = "androidx.activity.source_flexible_task_id"
        const val ZOOM_CALL_PACKAGE = "com.coloros.smartsidebar"
        const val START_ACTIVITY_AS_USER = "startActivityAsUser"
        const val LAUNCH_ROUTE_MAIN = "main"
        const val LAUNCH_ROUTE_AS_USER = "startActivityAsUser"
        const val LAUNCH_ROUTE_USER_CONTEXT = "userContext"
    }
}

internal sealed interface FreeformLaunchResult {
    data class Started(
        val route: String,
    ) : FreeformLaunchResult

    data object TargetUnavailable : FreeformLaunchResult

    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable,
    ) : FreeformLaunchResult
}
