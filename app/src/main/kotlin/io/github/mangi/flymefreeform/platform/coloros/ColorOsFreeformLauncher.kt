package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.Process

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

    fun launch(shortcut: AppShortcut): FreeformLaunchResult {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
            ?: return FreeformLaunchResult.TargetUnavailable
        return try {
            launcherApps.startShortcut(shortcut.packageName, shortcut.id, null, createShortcutLaunchOptions(), Process.myUserHandle())
            FreeformLaunchResult.Started
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_SHORTCUT_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_SHORTCUT_START_FAILED", exception)
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

    private fun createLaunchOptions(options: ActivityOptions = ActivityOptions.makeBasic()): Bundle {
        applyWindowingMode(options)
        // 厂商字段最后写入，避免 ActivityOptions 的默认值覆盖小窗参数。
        return options.toBundle().apply {
            putInt(ZOOM_FLAGS_KEY, ZOOM_LAUNCH_FLAG)
            putInt(WINDOWING_MODE_KEY, FLEXIBLE_WINDOWING_MODE)
        }
    }

    /** 仅用于已通过环境检查的用户手势提交，不为普通后台调用授予启动能力。 */
    @Suppress("DEPRECATION")
    private fun createShortcutLaunchOptions(): Bundle {
        // startShortcut 以发布者身份启动，system_server 是 realCaller。
        // Android 16 的旧 ALLOWED 模式不包含 realCaller 的系统 UID/BAL 权限豁免；
        // 目标进程不存在时必须显式 ALLOW_ALWAYS，否则会返回 BAL_BLOCK。
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        return createLaunchOptions(
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode),
        )
    }

    /** 隐藏 setter 只是补强；ColorOS 的两个 Bundle 参数才是不可缺少的启动协议。 */
    @SuppressLint("BlockedPrivateApi")
    private fun applyWindowingMode(options: ActivityOptions) {
        try {
            ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
                .invoke(options, FLEXIBLE_WINDOWING_MODE)
        } catch (_: ReflectiveOperationException) {
            // 保留同一个 options 上的公开 BAL 设置，继续使用 ColorOS Bundle 协议。
        } catch (_: RuntimeException) {
            // 隐藏 API 不可用时不丢弃公开参数。
        }
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
