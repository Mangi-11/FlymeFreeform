package io.github.mangi.flymefreeform.platform.coloros

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.window.AllAppsActionHandoff

/** 受系统服务权限及 UID 双重约束的打开协议；图标、条目与执行对象始终留在侧边栏进程。 */
internal class ColorOsAllAppsEndpoint(
    val service: Service,
    private val loader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private var request: Request? = null
    private var lastResult: Pair<String, Int>? = null
    private var disposed = false
    private var watching = false
    private var watchingPackages = false
    private var watchingConfiguration = false
    private var lastFailure = -5_000L
    private val tick = Runnable { advance() }
    private val refresh = Runnable { safely { request?.content?.refresh() } }
    private val settingsObserver: (ModuleSettingsSnapshot) -> Unit = { if (!it.enabled) handler.post { cancel() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = cancel()
    }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 300L)
        }
    }
    private val components = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = cancel()
        @Suppress("OVERRIDE_DEPRECATION") override fun onLowMemory() = Unit
    }

    val binder: IBinder get() = messenger.binder

    init { configuration.observe(settingsObserver) }

    fun onUnbound(id: String?) {
        request?.takeIf { it.id == id && it.phase != Phase.Active }?.let { finish(it, SidebarProtocol.ABORTED) }
    }

    fun onNativeSidebarState(state: String?) {
        if (state != "FLOAT_BAR_SHOWING") cancel()
    }

    fun onAdapterCreated(view: Any?, adapter: Any?) {
        val current = request ?: return
        if (current.content?.view !== view || adapter == null) return
        current.content?.bindAdapter(adapter) { tool, execute ->
            safely {
                if (request !== current || current.phase != Phase.Active || !environmentAllowed()) return@safely
                val window = current.window ?: return@safely
                if (!window.isShown()) return@safely
                when (current.action.select(tool)) {
                    AllAppsActionHandoff.Action.Ignore -> Unit
                    AllAppsActionHandoff.Action.ExecuteNow -> {
                        execute()
                        if (request === current) window.beginItemClick()
                    }
                    AllAppsActionHandoff.Action.ExecuteWhenHidden -> window.beginItemClick {
                        if (request === current && environmentAllowed() && current.action.hidden()) execute()
                    }
                }
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        cancel()
        handler.removeCallbacksAndMessages(null)
        configuration.removeObserver(settingsObserver)
    }

    private fun receive(message: Message): Boolean {
        if (disposed || !SidebarProtocol.isTrustedPeer(message.sendingUid, Process.SYSTEM_UID, message.arg1)) return true
        safely {
            val data = message.peekData() ?: return@safely
            val id = data.getString(SidebarProtocol.REQUEST_ID) ?: return@safely
            if (!SidebarProtocol.isValidRequestId(id) || data.getInt(SidebarProtocol.TARGET_UID, -1) != Process.myUid()) return@safely
            val reply = message.replyTo ?: return@safely
            if (message.what == SidebarProtocol.BACKDROP_HIDDEN) {
                val current = request?.takeIf { it.id == id && it.reply.binder == reply.binder } ?: return@safely
                val pending = current.pendingTool ?: return@safely
                current.pendingTool = null
                handler.removeCallbacks(current.toolTimeout)
                if (environmentAllowed()) pending() else cancel()
                return@safely
            }
            if (message.what == SidebarProtocol.CANCEL) {
                request?.takeIf { it.id == id && it.reply.binder == reply.binder }?.let {
                    finish(it, SidebarProtocol.CLEANED)
                    return@safely
                }
            }
            lastResult?.takeIf { it.first == id }?.let {
                send(reply, id, it.second)
                return@safely
            }
            if (message.what == SidebarProtocol.PREPARE) {
                prepare(id, data.getLong(SidebarProtocol.DEADLINE), reply)
                return@safely
            }
            val current = request?.takeIf { it.id == id && it.reply.binder == reply.binder } ?: return@safely
            when (message.what) {
                SidebarProtocol.OPEN -> {
                    val now = SystemClock.uptimeMillis()
                    val deadline = data.getLong(SidebarProtocol.DEADLINE)
                    if (current.phase != Phase.Prepared) return@safely
                    if (now >= current.deadline || !SidebarProtocol.isValidDeadline(deadline, now, SidebarProtocol.OPEN_TIMEOUT_MS) || !environmentAllowed() || current.content?.sidebarHidden() != true) {
                        finish(current, SidebarProtocol.CLEANED)
                        return@safely
                    }
                    current.deadline = deadline
                    current.phase = Phase.Opening
                    val content = current.content ?: return@safely
                    val window = ColorOsAllAppsWindow(service, content,
                        onShown = {
                            if (request === current && current.phase == Phase.Opening) {
                                current.phase = Phase.Shown
                                send(current.reply, current.id, SidebarProtocol.SHOWN)
                            }
                        },
                        onClosed = { finish(current, if (current.phase == Phase.Active || current.exitStarted) SidebarProtocol.ABORTED else SidebarProtocol.CLEANED) },
                        onExitStarted = {
                            current.exitStarted = true
                            send(current.reply, current.id, SidebarProtocol.EXIT_STARTED)
                        },
                        beforeTool = { execute ->
                            if (request === current) {
                                current.pendingTool = execute
                                handler.postDelayed(current.toolTimeout, SidebarProtocol.CLEANUP_TIMEOUT_MS)
                                send(current.reply, current.id, SidebarProtocol.HIDE_BACKDROP)
                            }
                        },
                        onFailure = ::reportFailure,
                    )
                    current.window = window
                    window.show()
                    schedule()
                }
                SidebarProtocol.CONFIRM -> {
                    if (current.phase != Phase.Shown) return@safely
                    if (SystemClock.uptimeMillis() >= current.deadline || !environmentAllowed() || current.window?.isShown() != true) {
                        finish(current, SidebarProtocol.CLEANED)
                    } else {
                        current.phase = Phase.Active
                        handler.removeCallbacks(tick)
                        lastResult = current.id to SidebarProtocol.COMMITTED
                        send(current.reply, current.id, SidebarProtocol.COMMITTED)
                    }
                }
                SidebarProtocol.CANCEL -> finish(current, SidebarProtocol.CLEANED)
            }
        }
        return true
    }

    private fun prepare(id: String, deadline: Long, reply: Messenger) {
        if (request != null || !environmentAllowed() || !SidebarProtocol.isValidDeadline(deadline, SystemClock.uptimeMillis(), SidebarProtocol.PREPARE_TIMEOUT_MS)) {
            send(reply, id, SidebarProtocol.ABORTED)
            return
        }
        val next = Request(id, deadline, reply)
        request = next
        reply.binder.linkToDeath(next.death, 0)
        next.deathLinked = true
        service.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(SidebarProtocol.ACTION_USER_SWITCHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        watching = true
        service.registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        watchingPackages = true
        service.registerComponentCallbacks(components)
        watchingConfiguration = true
        next.content = ColorOsAllAppsContent(loader)
        advance()
    }

    private fun advance(): Unit = safely {
        handler.removeCallbacks(tick)
        val current = request ?: return@safely
        if (current.phase == Phase.Active) return@safely
        if (SystemClock.uptimeMillis() >= current.deadline) {
            finish(current, SidebarProtocol.CLEANED)
            return@safely
        }
        val content = current.content
        if (current.phase == Phase.Preparing && content?.ready() == true) {
            if (!content.sidebarHidden()) {
                finish(current, SidebarProtocol.ABORTED)
                return@safely
            }
            content.startLoading()
            current.phase = Phase.Prepared
            send(current.reply, current.id, SidebarProtocol.READY)
        }
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (request != null) handler.postDelayed(tick, 32L)
    }

    private fun environmentAllowed(): Boolean =
        configuration.isAvailable && configuration.snapshot.enabled &&
            service.getSystemService(UserManager::class.java)?.isUserForeground == true &&
            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            Settings.Secure.getInt(service.contentResolver, "edge_panel_toggle", -1) == 1

    private fun cancel() { request?.let { finish(it, SidebarProtocol.ABORTED) } }

    private fun finish(current: Request, result: Int) {
        if (request !== current) return
        request = null
        current.action.cancel()
        current.pendingTool = null
        handler.removeCallbacks(current.toolTimeout)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(refresh)
        var cleaned = true
        fun cleanup(action: () -> Unit) {
            try { action() } catch (exception: Exception) { cleaned = false; reportFailure(exception) }
        }
        cleanup { current.window?.dispose() ?: current.content?.close() }
        if (current.deathLinked) cleanup { current.reply.binder.unlinkToDeath(current.death, 0) }
        if (watching) { watching = false; cleanup { service.unregisterReceiver(receiver) } }
        if (watchingPackages) { watchingPackages = false; cleanup { service.unregisterReceiver(packages) } }
        if (watchingConfiguration) { watchingConfiguration = false; cleanup { service.unregisterComponentCallbacks(components) } }
        val finalResult = if (cleaned && !(current.exitStarted && result == SidebarProtocol.CLEANED)) result else SidebarProtocol.ABORTED
        lastResult = current.id to finalResult
        send(current.reply, current.id, finalResult)
    }

    private fun send(reply: Messenger, id: String, what: Int) {
        try {
            reply.send(SidebarProtocol.message(what, id, 0, Process.myUid()))
        } catch (exception: RemoteException) {
            reportFailure(exception)
            if (request?.id == id) cancel()
        }
    }

    private inline fun safely(action: () -> Unit) {
        try { action() } catch (exception: Exception) {
            reportFailure(exception)
            request?.let { finish(it, SidebarProtocol.CLEANED) }
        }
    }

    private fun reportFailure(exception: Exception) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailure >= 5_000L) {
            lastFailure = now
            log(Log.WARN, "ALL_APPS_SESSION_FAILED", exception)
        }
    }

    private enum class Phase { Preparing, Prepared, Opening, Shown, Active }
    private inner class Request(val id: String, var deadline: Long, val reply: Messenger) {
        var phase = Phase.Preparing
        var exitStarted = false
        var pendingTool: (() -> Unit)? = null
        val toolTimeout = Runnable { if (request === this) cancel() }
        val action = AllAppsActionHandoff()
        var content: ColorOsAllAppsContent? = null
        var window: ColorOsAllAppsWindow? = null
        var deathLinked = false
        val death = IBinder.DeathRecipient { handler.post { if (request === this) cancel() } }
    }
}
