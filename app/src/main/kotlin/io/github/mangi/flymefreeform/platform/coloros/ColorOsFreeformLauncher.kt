package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** 每次提交都重新校验组件，并仅携带 ColorOS 自由窗参数启动，不退化为普通全屏启动。 */
internal class ColorOsFreeformLauncher(
    private val context: Context,
) {
    fun launch(component: ComponentName): FreeformLaunchResult {
        if (!isLaunchable(component)) return FreeformLaunchResult.TargetUnavailable

        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = createLaunchOptions()

        return try {
            context.startActivity(intent, options)
            FreeformLaunchResult.Started
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_START_FAILED", exception)
        }
    }

    private fun isLaunchable(component: ComponentName): Boolean =
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
    }
}

internal sealed interface FreeformLaunchResult {
    data object Started : FreeformLaunchResult

    data object TargetUnavailable : FreeformLaunchResult

    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable,
    ) : FreeformLaunchResult
}
