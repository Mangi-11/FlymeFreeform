package io.github.mangi.flymefreeform.hook

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import java.util.concurrent.CopyOnWriteArraySet

/** 进程内共享的运行环境；设置与显示尺寸只在启动和系统通知时读取。 */
internal class ModuleEnvironmentState(
    private val configuration: ProcessConfiguration,
    private val onFailure: (String, RuntimeException) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private var context: Context? = null
    private var displays: DisplayManager? = null
    private var keyguard: KeyguardManager? = null
    private var watchingSettings = false
    private var watchingDisplay = false
    private var watchingLock = false

    @Volatile private var monitoring = false
    @Volatile private var displayAvailable = false
    @Volatile private var portrait = false
    @Volatile private var gameModeActive = true
    @Volatile private var gestureNavigation = false
    @Volatile private var keyguardLocked = true
    @Volatile private var keyguardRecheckNeeded = true
    private var lastObservation: Observation? = null
    private var lastFailureAt = -10_000L

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshSettings()
            notifyIfChanged()
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateDisplay(displayId)
        override fun onDisplayChanged(displayId: Int) = updateDisplay(displayId)
        override fun onDisplayRemoved(displayId: Int) = updateDisplay(displayId)
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    keyguardLocked = true
                    keyguardRecheckNeeded = true
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_USER_UNLOCKED -> refreshKeyguardState()
                else -> return
            }
            notifyIfChanged()
        }
    }

    /** 长期 Hook 所有者与进程同寿命；Service 所有者必须在销毁时调用 close。 */
    fun start(owner: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (context != null) return
        val appContext = owner.applicationContext ?: owner
        context = appContext
        try {
            displays = appContext.getSystemService(DisplayManager::class.java)
            keyguard = appContext.getSystemService(KeyguardManager::class.java)
            val displayManager = displays ?: throw IllegalStateException("DisplayManager unavailable")
            appContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(GAME_MODE_KEY), false, settingsObserver,
            )
            watchingSettings = true
            appContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(NAVIGATION_MODE_KEY), false, settingsObserver,
            )
            displayManager.registerDisplayListener(displayListener, handler)
            watchingDisplay = true
            appContext.registerReceiver(lockReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_USER_UNLOCKED)
            // USER_PRESENT 由独立 UID 的 SystemUI 发送；非导出接收器会漏掉解锁恢复。
            // 这里只接收上述受系统保护的 action，并重新查询 KeyguardManager，不信任广播载荷。
            }, Context.RECEIVER_EXPORTED)
            watchingLock = true
            refreshSettings()
            refreshDisplay()
            refreshKeyguardState()
            monitoring = true
        } catch (exception: RuntimeException) {
            releaseObservers()
            reportFailure("MODULE_ENVIRONMENT_MONITOR_FAILED", exception)
        }
        notifyIfChanged()
    }

    fun observe(observer: () -> Unit) { observers += observer }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        monitoring = false
        releaseObservers()
        observers.clear()
        handler.removeCallbacksAndMessages(null)
        context = null
        displays = null
        keyguard = null
    }

    fun isModuleAllowed(): Boolean {
        val settings = configuration.snapshot
        return monitoring && displayAvailable && configuration.isAvailable && settings.enabled &&
            !settings.isPausedByEnvironment(landscape = !portrait, gameMode = gameModeActive)
    }

    fun isGestureAllowed(refreshKeyguard: Boolean = false): Boolean {
        if (refreshKeyguard && keyguardRecheckNeeded) refreshKeyguardState()
        return isModuleAllowed() && gestureNavigation && !keyguardLocked
    }

    private fun updateDisplay(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        refreshDisplay()
        notifyIfChanged()
    }

    @Suppress("DEPRECATION")
    private fun refreshDisplay() {
        portrait = try {
            // 使用默认物理显示的当前尺寸，避免桌面或小窗的固定竖屏 Configuration 掩盖横屏。
            val size = Point()
            displays?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
            displayAvailable = size.x > 0 && size.y > 0
            size.x > 0 && size.y > size.x
        } catch (exception: RuntimeException) {
            displayAvailable = false
            reportFailure("MODULE_ENVIRONMENT_DISPLAY_FAILED", exception)
            false
        }
    }

    private fun refreshSettings() {
        val resolver = context?.contentResolver ?: return
        try {
            gameModeActive = Settings.Global.getInt(resolver, GAME_MODE_KEY, 0) == 1
            gestureNavigation = Settings.Secure.getInt(resolver, NAVIGATION_MODE_KEY, -1) == 2
        } catch (exception: RuntimeException) {
            gameModeActive = true
            gestureNavigation = false
            reportFailure("MODULE_ENVIRONMENT_SETTINGS_FAILED", exception)
        }
    }

    private fun refreshKeyguardState() {
        keyguardLocked = try {
            keyguard?.isKeyguardLocked != false
        } catch (_: RuntimeException) {
            true
        }
        keyguardRecheckNeeded = keyguardLocked
    }

    private fun notifyIfChanged() {
        val observation = Observation(monitoring, displayAvailable, portrait, gameModeActive, gestureNavigation, keyguardLocked)
        if (observation == lastObservation) return
        lastObservation = observation
        observers.forEach { observer ->
            try { observer() } catch (exception: RuntimeException) {
                reportFailure("MODULE_ENVIRONMENT_CALLBACK_FAILED", exception)
            }
        }
    }

    private fun releaseObservers() {
        fun release(action: () -> Unit) {
            try { action() } catch (exception: RuntimeException) {
                reportFailure("MODULE_ENVIRONMENT_CLEANUP_FAILED", exception)
            }
        }
        if (watchingSettings) {
            watchingSettings = false
            release { context?.contentResolver?.unregisterContentObserver(settingsObserver) }
        }
        if (watchingDisplay) {
            watchingDisplay = false
            release { displays?.unregisterDisplayListener(displayListener) }
        }
        if (watchingLock) {
            watchingLock = false
            release { context?.unregisterReceiver(lockReceiver) }
        }
    }

    private fun reportFailure(code: String, exception: RuntimeException) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureAt < 10_000L) return
        lastFailureAt = now
        onFailure(code, exception)
    }

    private data class Observation(
        val monitoring: Boolean,
        val displayAvailable: Boolean,
        val portrait: Boolean,
        val gameModeActive: Boolean,
        val gestureNavigation: Boolean,
        val keyguardLocked: Boolean,
    )

    private companion object {
        const val GAME_MODE_KEY = "debug_gamemode_value"
        const val NAVIGATION_MODE_KEY = "navigation_mode"
    }
}
