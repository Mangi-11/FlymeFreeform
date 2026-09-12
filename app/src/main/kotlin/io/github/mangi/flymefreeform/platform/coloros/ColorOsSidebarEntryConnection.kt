package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.github.mangi.flymefreeform.apps.CatalogTransfer
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** system_server 的短连接；不在主线程查询包、唤起常驻端或等待 Binder。 */
internal class ColorOsSidebarEntryConnection(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "FlymeFreeform-EntryTransport") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val reply = Messenger(Handler(handler.looper, ::receive))
    private var current: Request? = null
    private var lastFailure = -5_000L

    fun request(
        target: PinnedTarget.Sidebar? = null,
        beforeLaunch: () -> Boolean = { true },
        onResult: (List<SelectableEntry>?, Boolean) -> Unit,
    ): Boolean {
        if (current != null) return false
        val request = Request(target, beforeLaunch, onResult)
        current = request
        handler.postAtTime(request.timeout, request.deadline)
        work(request) {
            val userId = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
            val user = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId) as UserHandle
            val userContext = Context::class.java.getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
                .invoke(context, user, 0) as Context
            request.userId = userId
            request.userContext = userContext
            val uid = ColorOsSidebarTarget.supportedUid(userContext)
            if (uid == null || userContext.getSystemService(UserManager::class.java)?.isUserForeground != true ||
                Settings.Secure.getInt(userContext.contentResolver, "edge_panel_toggle", -1) != 1 ||
                (target != null && target.userId != userId)
            ) {
                handler.post { finish(request) }
                return@work
            }
            request.uid = uid
            if (!request.active.get()) return@work
            val provider = userContext.contentResolver.acquireUnstableContentProviderClient(ColorOsSidebarTarget.PROVIDER_AUTHORITY)
                ?: throw IllegalStateException("SIDEBAR_ENTRY_PROVIDER_UNAVAILABLE")
            provider.use { it.call(ColorOsSidebarTarget.PREPARE_METHOD, null, null) }
            if (!request.active.get()) return@work
            val intent = Intent(SidebarEntryProtocol.BIND_ACTION).setComponent(COMPONENT).setIdentifier(request.id)
            request.bound.set(userContext.bindService(intent, Context.BIND_AUTO_CREATE, { action -> handler.post(action) }, request.connection))
            handler.post {
                if (!request.active.get()) unbind(request)
                else if (!request.bound.get()) finish(request)
            }
        }
        return true
    }

    fun cancel() { current?.let(::finish) }

    private fun receive(message: Message): Boolean {
        val request = current ?: return true
        if (message.sendingUid != request.uid || message.arg1 != SidebarEntryProtocol.VERSION) return true
        try {
            val data = message.peekData() ?: return true
            if (data.getString(SidebarEntryProtocol.ID) != request.id) return true
            if (SystemClock.uptimeMillis() >= request.deadline) { finish(request); return true }
            when (message.what) {
                SidebarEntryProtocol.PREPARED -> {
                    if (request.target == null || request.confirmed) return true
                    if (!request.beforeLaunch()) { finish(request); return true }
                    request.confirmed = true
                    work(request) {
                        request.remote?.send(SidebarEntryProtocol.message(SidebarEntryProtocol.EXECUTE, request.id, request.deadline, reply))
                    }
                }
                SidebarEntryProtocol.ITEM -> {
                    if (request.target != null) { finish(request); return true }
                    val entry = SidebarEntryProtocol.readEntry(data, request.userId)
                    if (entry == null || !request.transfer.append(data.getInt(SidebarEntryProtocol.INDEX, -1), entry)) finish(request)
                }
                SidebarEntryProtocol.COMPLETE -> {
                    val entries = if (request.target == null) request.transfer.complete(data.getInt(SidebarEntryProtocol.COUNT, -1)) else null
                    finish(request, entries)
                }
                SidebarEntryProtocol.DISPATCHED -> finish(request, dispatched = request.target != null && request.confirmed)
                SidebarEntryProtocol.FAILED -> finish(request)
            }
        } catch (exception: RuntimeException) { report(exception); finish(request) }
        return true
    }

    private fun connected(request: Request, binder: IBinder) {
        if (current !== request) return
        work(request) {
            if (binder.interfaceDescriptor != SidebarProtocol.DESCRIPTOR) throw IllegalStateException("SIDEBAR_ENTRY_DESCRIPTOR_MISMATCH")
            binder.linkToDeath(request.death, 0)
            handler.post {
                try {
                    if (current !== request) {
                        unlink(request, binder)
                    } else {
                        request.remote = Messenger(binder)
                        if (request.target != null && !request.beforeLaunch()) { finish(request); return@post }
                        work(request) {
                            val message = SidebarEntryProtocol.message(
                                if (request.target == null) SidebarEntryProtocol.LIST else SidebarEntryProtocol.LAUNCH,
                                request.id, request.deadline, reply,
                            )
                            request.target?.let { message.data.putString(SidebarEntryProtocol.TARGET, PinnedTargetCodec.encode(it)) }
                            if (request.active.get()) request.remote?.send(message)
                        }
                    }
                } catch (exception: RuntimeException) { report(exception); finish(request) }
            }
        }
    }

    private fun finish(request: Request, entries: List<SelectableEntry>? = null, dispatched: Boolean = false) {
        if (current !== request) return
        current = null
        request.active.set(false)
        request.transfer.cancel()
        handler.removeCallbacks(request.timeout)
        val remote = request.remote
        request.remote = null
        try {
            worker.execute {
                try {
                    remote?.send(SidebarEntryProtocol.message(SidebarEntryProtocol.CANCEL, request.id, reply = reply))
                } catch (exception: Exception) { handler.post { report(exception) } }
                finally { remote?.binder?.let { unlink(request, it) }; unbind(request) }
            }
        } catch (exception: RuntimeException) {
            remote?.binder?.let { unlink(request, it) }
            unbind(request)
            report(exception)
        }
        try { request.onResult(entries, dispatched) }
        catch (exception: RuntimeException) { report(exception) }
    }

    private fun unlink(request: Request, binder: IBinder) {
        try { binder.unlinkToDeath(request.death, 0) }
        catch (exception: RuntimeException) { handler.post { report(exception) } }
    }

    private fun unbind(request: Request) {
        if (!request.bound.compareAndSet(true, false)) return
        try { request.userContext?.unbindService(request.connection) }
        catch (exception: RuntimeException) { handler.post { report(exception) } }
    }

    private fun work(request: Request, action: () -> Unit) {
        try {
            worker.execute {
                if (!request.active.get()) return@execute
                try { action() }
                catch (exception: Exception) {
                    if (exception is InvocationTargetException && exception.cause is Error) throw exception.cause as Error
                    handler.post { report(exception); finish(request) }
                }
            }
        } catch (exception: RuntimeException) { report(exception); finish(request) }
    }

    private fun report(exception: Exception) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailure >= 5_000L) {
            lastFailure = now
            log(Log.WARN, "SIDEBAR_ENTRY_TRANSPORT_FAILED", exception)
        }
    }

    private inner class Request(
        val target: PinnedTarget.Sidebar?, val beforeLaunch: () -> Boolean,
        val onResult: (List<SelectableEntry>?, Boolean) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        val deadline = SystemClock.uptimeMillis() + SidebarEntryProtocol.TIMEOUT_MS
        val active = AtomicBoolean(true)
        val bound = AtomicBoolean(false)
        @Volatile var userId = -1
        @Volatile var uid = -1
        @Volatile var userContext: Context? = null
        @Volatile var remote: Messenger? = null
        var confirmed = false
        val transfer = CatalogTransfer(SidebarEntryProtocol.MAX_ENTRIES, SelectableEntry::target)
        val timeout = Runnable { finish(this) }
        val death = IBinder.DeathRecipient { handler.post { finish(this) } }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (name == COMPONENT) connected(this@Request, service) else finish(this@Request)
            }
            override fun onNullBinding(name: ComponentName) = finish(this@Request)
            override fun onServiceDisconnected(name: ComponentName) = finish(this@Request)
            override fun onBindingDied(name: ComponentName) = finish(this@Request)
        }
    }

    private companion object {
        val COMPONENT = ComponentName(ColorOsSidebarTarget.PACKAGE_NAME, ColorOsSidebarTarget.SERVICE_CLASS)
    }
}
