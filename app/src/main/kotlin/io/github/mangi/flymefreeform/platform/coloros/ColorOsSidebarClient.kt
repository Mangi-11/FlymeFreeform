package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import io.github.mangi.flymefreeform.window.MorePanelSession
import java.util.UUID
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** system_server 只持有一次交接；包查询、绑定和 Binder 发送均在有界工作队列执行。 */
@SuppressLint("PrivateApi")
internal class ColorOsSidebarClient(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    enum class Outcome { Shown, Fallback, Abandoned }

    private val worker =
        ThreadPoolExecutor(
            1, 1, 10L, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "FlymeFreeform-Sidebar") },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var current: Request? = null
    private val reply = Messenger(Handler(handler.looper, ::receive))
    private var lastFailureAt = -5_000L

    val isPending: Boolean get() = current?.committed == false

    fun open(
        beforeOpen: () -> Boolean, onResult: (Outcome) -> Unit,
        onExitStarted: () -> Unit, onHideBackdrop: (() -> Unit) -> Unit, onClosed: () -> Unit,
    ): Boolean {
        if (current != null) return false
        val request =
            Request(
                MorePanelSession(UUID.randomUUID().toString(), SystemClock.uptimeMillis() + SidebarProtocol.PREPARE_TIMEOUT_MS),
                beforeOpen, onResult, onExitStarted, onHideBackdrop, onClosed,
            )
        current = request
        try {
            request.watch()
            scheduleTimeout(request)
            execute(request) { prepareAndBind(request) }
        } catch (exception: RuntimeException) {
            logFailure("SIDEBAR_BIND_FAILED", exception)
            apply(request, request.session.cleaned(request.session.id))
        }
        return true
    }

    fun cancel() {
        val request = current ?: return
        if (request.committed) {
            if (request.closing) return
            request.closing = true
            request.session.deadline = SystemClock.uptimeMillis() + SidebarProtocol.CLEANUP_TIMEOUT_MS
            send(request, SidebarProtocol.CANCEL)
            scheduleTimeout(request)
            return
        }
        apply(request, request.session.cancel(allowFallback = false))
    }

    private fun prepareAndBind(request: Request) {
        val userId = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        val user = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId) as UserHandle
        val userContext =
            Context::class.java.getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
                .invoke(context, user, 0) as Context
        request.userContext = userContext
        val uid = ColorOsSidebarTarget.supportedUid(userContext)
        if (userContext.getSystemService(UserManager::class.java)?.isUserForeground != true) {
            handler.post { apply(request, request.session.abort(request.session.id)) }
            return
        }
        if (uid == null ||
            Settings.Secure.getInt(userContext.contentResolver, "edge_panel_toggle", -1) != 1
        ) {
            handler.post { apply(request, request.session.cleaned(request.session.id)) }
            return
        }
        request.targetUid = uid
        if (!request.active.get() || SystemClock.uptimeMillis() >= request.session.deadline) return
        // 让常驻端按 NONE 任务绑定 UI 并注册数据回调；单独创建 UIService 不会完成这次握手。
        val provider = userContext.contentResolver.acquireUnstableContentProviderClient(ColorOsSidebarTarget.PROVIDER_AUTHORITY)
            ?: throw IllegalStateException("SIDEBAR_PREPARE_PROVIDER_UNAVAILABLE")
        provider.use { it.call(ColorOsSidebarTarget.PREPARE_METHOD, null, null) }
        if (!request.active.get() || SystemClock.uptimeMillis() >= request.session.deadline) return
        val intent = Intent(ColorOsSidebarTarget.BIND_ACTION).setComponent(COMPONENT).setIdentifier(request.session.id)
        val bound = userContext.bindService(intent, Context.BIND_AUTO_CREATE, { task -> handler.post(task) }, request.connection)
        request.bound.set(bound)
        handler.post {
            if (!request.active.get()) unbind(request)
            else if (!bound) apply(request, request.session.cleaned(request.session.id))
        }
    }

    private fun receive(message: Message): Boolean {
        val request = current ?: return true
        if (!SidebarProtocol.isTrustedPeer(message.sendingUid, request.targetUid, message.arg1)) return true
        try {
            val id = message.peekData()?.getString(SidebarProtocol.REQUEST_ID) ?: return true
            if (id == request.session.id && message.what == SidebarProtocol.EXIT_STARTED) {
                request.onExitStarted?.invoke()
                return true
            }
            if (request.committed && id == request.session.id && message.what == SidebarProtocol.HIDE_BACKDROP) {
                request.onHideBackdrop?.invoke {
                    if (current === request && !request.closing) send(request, SidebarProtocol.BACKDROP_HIDDEN)
                }
                return true
            }
            if (request.committed && id == request.session.id &&
                message.what in setOf(SidebarProtocol.CLEANED, SidebarProtocol.ABORTED)
            ) {
                finish(request, Outcome.Abandoned)
                return true
            }
            val now = SystemClock.uptimeMillis()
            val effect =
                when (message.what) {
                    SidebarProtocol.READY -> request.session.ready(id, now, now + SidebarProtocol.OPEN_TIMEOUT_MS)
                    SidebarProtocol.SHOWN -> request.session.shown(id, now)
                    SidebarProtocol.COMMITTED -> request.session.committed(id)
                    SidebarProtocol.CLEANED -> request.session.cleaned(id)
                    SidebarProtocol.ABORTED -> request.session.abort(id)
                    else -> MorePanelSession.Effect.None
                }
            apply(request, effect)
        } catch (exception: RuntimeException) {
            logFailure("SIDEBAR_REPLY_INVALID", exception)
            apply(request, request.session.cancel(allowFallback = true))
        }
        return true
    }

    private fun apply(request: Request, effect: MorePanelSession.Effect) {
        if (current !== request) return
        when (effect) {
            MorePanelSession.Effect.None -> Unit
            MorePanelSession.Effect.Open -> {
                if (request.beforeOpen?.invoke() == true) {
                    send(request, SidebarProtocol.OPEN)
                    scheduleTimeout(request)
                } else {
                    apply(request, request.session.cancel(allowFallback = false))
                }
            }
            MorePanelSession.Effect.Confirm -> send(request, SidebarProtocol.CONFIRM)
            MorePanelSession.Effect.Cancel -> {
                send(request, SidebarProtocol.CANCEL)
                request.session.deadline = SystemClock.uptimeMillis() + SidebarProtocol.CLEANUP_TIMEOUT_MS
                scheduleTimeout(request)
            }
            MorePanelSession.Effect.Fallback -> finish(request, Outcome.Fallback)
            MorePanelSession.Effect.Shown -> {
                // 窗口存活期间保留服务绑定，避免原生常驻端 stopService 后 UIService 被销毁。
                request.committed = true
                handler.removeCallbacks(request.timeout)
                val result = request.onResult
                request.beforeOpen = null
                request.onResult = null
                cleanup("SIDEBAR_RESULT_HANDLING_FAILED") { result?.invoke(Outcome.Shown) }
            }
            MorePanelSession.Effect.Abandon -> finish(request, Outcome.Abandoned)
        }
    }

    private fun send(request: Request, what: Int) {
        val remote = request.remote ?: return
        val deadline = request.session.deadline
        execute(request) {
            if (request.active.get()) {
                remote.send(SidebarProtocol.message(what, request.session.id, deadline, request.targetUid, reply))
            }
        }
    }

    private fun scheduleTimeout(request: Request) {
        handler.removeCallbacks(request.timeout)
        handler.postAtTime(request.timeout, request.session.deadline)
    }

    private fun execute(request: Request, block: () -> Unit) {
        try {
            worker.execute {
                if (!request.active.get()) return@execute
                try {
                    block()
                } catch (exception: ReflectiveOperationException) {
                    if (exception is InvocationTargetException && exception.cause is Error) throw exception.cause as Error
                    transportFailure(request, exception)
                } catch (exception: RemoteException) {
                    transportFailure(request, exception)
                } catch (exception: RuntimeException) {
                    transportFailure(request, exception)
                }
            }
        } catch (exception: RejectedExecutionException) {
            transportFailure(request, exception)
        }
    }

    private fun transportFailure(request: Request, exception: Exception) {
        handler.post {
            if (current !== request) return@post
            logFailure("SIDEBAR_TRANSPORT_FAILED", exception)
            if (request.committed) finish(request, Outcome.Abandoned)
            else apply(request, request.session.cancel(allowFallback = true))
        }
    }

    private fun finish(request: Request, outcome: Outcome) {
        if (current !== request) return
        current = null
        request.active.set(false)
        val onResult = request.onResult
        val onClosed = request.onClosed.takeIf { request.committed }
        request.beforeOpen = null
        request.onResult = null
        request.onClosed = null
        request.onExitStarted = null
        request.onHideBackdrop = null
        handler.removeCallbacks(request.timeout)
        request.stopWatching()
        cleanup("SIDEBAR_DEATH_LISTENER_REMOVE_FAILED") {
            request.remote?.binder?.unlinkToDeath(request.death, 0)
        }
        request.remote = null
        unbind(request)
        cleanup("SIDEBAR_RESULT_HANDLING_FAILED") { onResult?.invoke(outcome) }
        cleanup("SIDEBAR_CLOSE_HANDLING_FAILED") { onClosed?.invoke() }
    }

    private fun unbind(request: Request) {
        val userContext = request.userContext ?: return
        if (!request.bound.compareAndSet(true, false)) return
        try {
            worker.execute {
                try {
                    userContext.unbindService(request.connection)
                } catch (exception: RuntimeException) {
                    handler.post { logFailure("SIDEBAR_UNBIND_FAILED", exception) }
                }
            }
        } catch (exception: RejectedExecutionException) {
            // 队列满时仍需释放已建立的连接；此处没有等待远端 Binder 返回结果。
            cleanup("SIDEBAR_UNBIND_FAILED") { userContext.unbindService(request.connection) }
            logFailure("SIDEBAR_UNBIND_QUEUE_FULL", exception)
        }
    }

    private fun cleanup(code: String, action: () -> Unit) {
        try {
            action()
        } catch (exception: RuntimeException) {
            logFailure(code, exception)
        }
    }

    private fun logFailure(code: String, exception: Exception? = null) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureAt >= 5_000L) {
            lastFailureAt = now
            log(Log.WARN, code, exception)
        }
    }

    private inner class Request(
        val session: MorePanelSession,
        var beforeOpen: (() -> Boolean)?,
        var onResult: ((Outcome) -> Unit)?,
        var onExitStarted: (() -> Unit)?,
        var onHideBackdrop: ((() -> Unit) -> Unit)?,
        var onClosed: (() -> Unit)?,
    ) : ComponentCallbacks, DisplayManager.DisplayListener {
        val active = AtomicBoolean(true)
        val bound = AtomicBoolean(false)
        @Volatile var userContext: Context? = null
        @Volatile var targetUid = -1
        var remote: Messenger? = null
        var committed = false
        var closing = false
        private var receiverRegistered = false
        private var componentsRegistered = false
        private var displayRegistered = false
        private val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        val timeout = Runnable {
            if (current === this) {
                if (committed) {
                    logFailure("SIDEBAR_CLEANUP_TIMEOUT")
                    finish(this, Outcome.Abandoned)
                    return@Runnable
                }
                logFailure(
                    when (session.phase) {
                        MorePanelSession.Phase.Preparing -> "SIDEBAR_PREPARE_TIMEOUT"
                        MorePanelSession.Phase.Cancelling -> "SIDEBAR_CLEANUP_TIMEOUT"
                        else -> "SIDEBAR_OPEN_TIMEOUT"
                    },
                )
                apply(this, session.timeout())
            }
        }
        val death = IBinder.DeathRecipient {
            handler.post {
                // Messenger Binder 随侧边栏进程死亡，原进程的窗口也由系统回收。
                if (current === this) {
                    if (committed) finish(this, Outcome.Abandoned)
                    else apply(this, session.cleaned(session.id))
                }
            }
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = invalidate()
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (current !== this@Request) return
                if (name != COMPONENT) {
                    apply(this@Request, session.cleaned(session.id))
                    return
                }
                execute(this@Request) {
                    if (service.interfaceDescriptor != SidebarProtocol.DESCRIPTOR) {
                        handler.post { apply(this@Request, session.cleaned(session.id)) }
                        return@execute
                    }
                    service.linkToDeath(death, 0)
                    handler.post {
                        if (current !== this@Request) {
                            service.unlinkToDeath(death, 0)
                        } else {
                            remote = Messenger(service)
                            send(this@Request, SidebarProtocol.PREPARE)
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (current === this@Request) {
                    if (committed) finish(this@Request, Outcome.Abandoned)
                    else apply(this@Request, session.cancel(allowFallback = true))
                }
            }

            override fun onBindingDied(name: ComponentName) {
                if (current === this@Request) {
                    if (committed) finish(this@Request, Outcome.Abandoned)
                    else apply(this@Request, session.cancel(allowFallback = true))
                }
            }

            override fun onNullBinding(name: ComponentName) {
                if (current === this@Request) apply(this@Request, session.cleaned(session.id))
            }
        }

        fun watch() {
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(SidebarProtocol.ACTION_USER_SWITCHED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            context.registerComponentCallbacks(this)
            componentsRegistered = true
            displayManager.registerDisplayListener(this, handler)
            displayRegistered = true
        }

        fun stopWatching() {
            if (receiverRegistered) {
                receiverRegistered = false
                cleanup("SIDEBAR_RECEIVER_REMOVE_FAILED") { context.unregisterReceiver(receiver) }
            }
            if (componentsRegistered) {
                componentsRegistered = false
                cleanup("SIDEBAR_CONFIGURATION_LISTENER_REMOVE_FAILED") { context.unregisterComponentCallbacks(this) }
            }
            if (displayRegistered) {
                displayRegistered = false
                cleanup("SIDEBAR_DISPLAY_LISTENER_REMOVE_FAILED") { displayManager.unregisterDisplayListener(this) }
            }
        }

        private fun invalidate() {
            if (current === this) cancel()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = invalidate()
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) invalidate()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && displayManager.getDisplay(displayId)?.rotation != rotation) invalidate()
        }
    }

    private companion object {
        val COMPONENT = ComponentName(ColorOsSidebarTarget.PACKAGE_NAME, ColorOsSidebarTarget.SERVICE_CLASS)
    }
}
