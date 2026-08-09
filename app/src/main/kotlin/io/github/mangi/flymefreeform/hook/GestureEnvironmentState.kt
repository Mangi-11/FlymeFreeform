package io.github.mangi.flymefreeform.hook

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** 进程级环境缓存；设置读取只发生在初始化和 ContentObserver 回调，不进入触摸热路径。 */
internal class GestureEnvironmentState(private val context: Context) {
    private val keyguardManager =
        try {
            context.getSystemService(KeyguardManager::class.java)
        } catch (_: RuntimeException) {
            null
        }

    @Volatile
    private var gestureNavigation = false

    @Volatile
    private var gameModeActive = true

    @Volatile
    private var keyguardLocked = true

    @Volatile
    private var keyguardRecheckNeeded = true

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }
    private val lockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        keyguardLocked = true
                        keyguardRecheckNeeded = true
                    }
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT,
                    Intent.ACTION_USER_UNLOCKED -> refreshKeyguardState()
                }
            }
        }

    init {
        refresh()
        refreshKeyguardState()
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(GAME_MODE_KEY),
                false,
                observer,
            )
            context.registerReceiver(
                lockReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(NAVIGATION_MODE_KEY),
                false,
                observer,
            )
        } catch (_: SecurityException) {
            gestureNavigation = false
            gameModeActive = true
            keyguardLocked = true
            keyguardRecheckNeeded = true
        } catch (_: RuntimeException) {
            gestureNavigation = false
            gameModeActive = true
            keyguardLocked = true
            keyguardRecheckNeeded = true
        }
    }

    fun isAllowed(refreshKeyguard: Boolean = false): Boolean {
        if (refreshKeyguard && keyguardRecheckNeeded) refreshKeyguardState()
        return gestureNavigation &&
            !gameModeActive &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            !keyguardLocked
    }

    private fun refreshKeyguardState() {
        keyguardLocked =
            try {
                keyguardManager?.isKeyguardLocked != false
            } catch (_: RuntimeException) {
                true
            }
        keyguardRecheckNeeded = keyguardLocked
    }

    private fun refresh() {
        try {
            gameModeActive =
                Settings.System.getString(context.contentResolver, GAME_MODE_KEY) == GAME_MODE_ACTIVE
            gestureNavigation =
                Settings.Secure.getInt(context.contentResolver, NAVIGATION_MODE_KEY, -1) ==
                    GESTURE_NAVIGATION_MODE
        } catch (_: SecurityException) {
            gestureNavigation = false
            gameModeActive = true
        } catch (_: RuntimeException) {
            gestureNavigation = false
            gameModeActive = true
        }
    }

    private companion object {
        const val GAME_MODE_KEY = "game_mode_status"
        const val GAME_MODE_ACTIVE = "1"
        const val NAVIGATION_MODE_KEY = "navigation_mode"
        const val GESTURE_NAVIGATION_MODE = 2
    }
}
