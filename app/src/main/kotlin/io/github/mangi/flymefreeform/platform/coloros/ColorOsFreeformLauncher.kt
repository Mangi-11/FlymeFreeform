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
    fun launch(target: AppTarget): FreeformLaunchResult =
        try {
            val launchContext = contextFor(target)
            if (!isLaunchable(launchContext, target.component)) {
                return FreeformLaunchResult.TargetUnavailable
            }
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(target.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = createLaunchOptions()
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

    private fun createLaunchOptions(): Bundle =
        Bundle().apply {
            putInt(ZOOM_FLAGS_KEY, ZOOM_LAUNCH_FLAG)
            putInt(WINDOWING_MODE_KEY, FLEXIBLE_WINDOWING_MODE)
            createPlatformOptions()?.let(::putAll)
        }

    /** 隐藏 setter 只是补强；ColorOS 的两个 Bundle 参数才是不可缺少的启动协议。 */
    @SuppressLint("BlockedPrivateApi")
    private fun createPlatformOptions(): Bundle? =
        try {
            ActivityOptions.makeBasic().let { options ->
                ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(options, FLEXIBLE_WINDOWING_MODE)
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
        const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
        const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"
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
