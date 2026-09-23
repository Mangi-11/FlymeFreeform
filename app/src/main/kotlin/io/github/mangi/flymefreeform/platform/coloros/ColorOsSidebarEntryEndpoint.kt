package io.github.mangi.flymefreeform.platform.coloros

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 目录与单项执行各占用一次有时限的绑定；任一调用都只接受 system UID。 */
internal class ColorOsSidebarEntryEndpoint(
    val service: Service,
    private val loader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "FlymeFreeform-Entries") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val messenger = Messenger(Handler(handler.looper, ::receive))
    private val history = EntryRequestHistory()
    private var current: Request? = null
    private var disposed = false
    private var lastFailure = -5_000L
    private val invalidation = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { current?.let(::finish) }
    }
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ModulePreferences.ACTION_PINNED_TARGETS_CHANGED ||
                sentFromUid != moduleUid()
            ) return
            val encoded = intent.getStringExtra(ModulePreferences.EXTRA_PINNED_TARGETS) ?: return
            configuration.override(
                configuration.snapshot.copy(
                    enabled = intent.getBooleanExtra(
                        ModulePreferences.EXTRA_MODULE_ENABLED,
                        configuration.snapshot.enabled,
                    ),
                    pinsSaved = intent.getBooleanExtra(
                        ModulePreferences.EXTRA_PINS_SAVED,
                        configuration.snapshot.pinsSaved,
                    ),
                    pinnedTargets = PinnedTargetCodec.decodeList(encoded),
                ),
            )
        }
    }

    val binder: IBinder get() = messenger.binder

    init {
        service.registerReceiver(invalidation, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(SidebarProtocol.ACTION_USER_SWITCHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        service.registerReceiver(
            settingsReceiver,
            IntentFilter(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
    }

    fun onUnbound(id: String?) { current?.takeIf { it.id == id }?.let(::finish) }

    fun dispose() {
        if (disposed) return
        disposed = true
        current?.let(::finish)
        try { service.unregisterReceiver(invalidation) }
        catch (exception: RuntimeException) { report(exception) }
        try { service.unregisterReceiver(settingsReceiver) }
        catch (exception: RuntimeException) { report(exception) }
        finally {
            worker.shutdownNow()
            handler.removeCallbacksAndMessages(null)
        }
    }

    private fun receive(message: Message): Boolean {
        if (disposed || message.sendingUid != Process.SYSTEM_UID || message.arg1 != SidebarEntryProtocol.VERSION) return true
        try {
            val data = message.peekData() ?: return true
            val id = data.getString(SidebarEntryProtocol.ID) ?: return true
            val reply = message.replyTo ?: return true
            if (!SidebarProtocol.isValidRequestId(id)) return true
            if (message.what == SidebarEntryProtocol.CANCEL) {
                current?.takeIf { it.id == id && it.reply.binder == reply.binder }?.let(::finish)
                return true
            }
            if (message.what == SidebarEntryProtocol.EXECUTE) {
                val request = current?.takeIf { it.id == id && it.reply.binder == reply.binder } ?: return true
                val entries = request.prepared ?: return true
                request.prepared = null
                val target = request.target ?: return true
                if (SystemClock.uptimeMillis() >= request.deadline || !launchAllowed(target) || !entries.sidebarHidden()) {
                    finish(request)
                    return true
                }
                execute(request) {
                    val bean = entries.resolve(target)
                    handler.post {
                        if (current !== request || !request.active.get()) return@post
                        try {
                            val dispatched = bean != null && SystemClock.uptimeMillis() < request.deadline &&
                                launchAllowed(target) && entries.sidebarHidden() && entries.launch(target, bean)
                            execute(request) {
                                send(request, SidebarEntryProtocol.message(
                                    if (dispatched) SidebarEntryProtocol.DISPATCHED else SidebarEntryProtocol.FAILED, request.id,
                                ))
                                handler.post { finish(request) }
                            }
                        } catch (exception: Exception) { report(exception); finish(request) }
                    }
                }
                return true
            }
            if (message.what != SidebarEntryProtocol.LIST && message.what != SidebarEntryProtocol.LAUNCH) return true
            if (current != null) return true
            val deadline = data.getLong(SidebarEntryProtocol.DEADLINE)
            if (!SidebarProtocol.isValidDeadline(deadline, SystemClock.uptimeMillis(), SidebarEntryProtocol.TIMEOUT_MS) || !allowed()) return true
            val target = if (message.what == SidebarEntryProtocol.LAUNCH) {
                data.getString(SidebarEntryProtocol.TARGET)?.let(PinnedTargetCodec::decode) as? PinnedTarget.Sidebar ?: return true
            } else null
            if (target != null && !launchAllowed(target)) return true
            if (!history.claim(id, deadline, SystemClock.uptimeMillis())) return true
            val request = Request(id, deadline, reply, target)
            current = request
            reply.binder.linkToDeath(request.death, 0)
            request.linked = true
            handler.postAtTime(request.timeout, deadline)
            execute(request) {
                val entries = ColorOsSidebarEntries(service, loader)
                handler.post { prepare(request, entries) }
            }
        } catch (exception: Exception) {
            report(exception)
            current?.let(::finish)
        }
        return true
    }

    private fun prepare(request: Request, entries: ColorOsSidebarEntries) {
        if (current !== request || !request.active.get()) return
        try {
            if (!allowed()) { finish(request); return }
            if (!entries.ready()) {
                handler.postDelayed({ prepare(request, entries) }, 50L)
                return
            }
            val target = request.target
            if (target == null) {
                execute(request) {
                    val loaded = entries.load(Process.myUid() / 100_000)
                    for ((index, entry) in loaded.withIndex()) {
                        if (!request.active.get() || SystemClock.uptimeMillis() >= request.deadline || !allowed()) return@execute
                        request.reply.send(SidebarEntryProtocol.item(request.id, index, entry))
                    }
                    send(request, SidebarEntryProtocol.message(SidebarEntryProtocol.COMPLETE, request.id).apply {
                        data.putInt(SidebarEntryProtocol.COUNT, loaded.size)
                    })
                    handler.post { finish(request) }
                }
            } else {
                if (!launchAllowed(target) || !entries.sidebarHidden()) { finish(request); return }
                request.prepared = entries
                execute(request) { send(request, SidebarEntryProtocol.message(SidebarEntryProtocol.PREPARED, request.id)) }
            }
        } catch (exception: Exception) { report(exception); finish(request) }
    }

    private fun allowed(): Boolean = configuration.isAvailable &&
        service.getSystemService(UserManager::class.java)?.isUserForeground == true &&
        service.getSystemService(UserManager::class.java)?.isUserUnlocked == true &&
        service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
        Settings.Secure.getInt(service.contentResolver, "edge_panel_toggle", -1) == 1

    private fun launchAllowed(target: PinnedTarget.Sidebar): Boolean = allowed() &&
        target.userId == (Process.myUid() / 100_000) && configuration.snapshot.enabled &&
        target in configuration.snapshot.pinnedTargets

    private fun execute(request: Request, action: () -> Unit) {
        try {
            worker.execute {
                if (!request.active.get()) return@execute
                try { action() } catch (exception: Exception) {
                    handler.post { report(exception); finish(request) }
                }
            }
        } catch (exception: RuntimeException) { report(exception); finish(request) }
    }

    private fun send(request: Request, message: Message) {
        if (request.active.get() && SystemClock.uptimeMillis() < request.deadline) request.reply.send(message)
    }

    private fun finish(request: Request) {
        if (current !== request) return
        current = null
        request.active.set(false)
        request.prepared = null
        handler.removeCallbacks(request.timeout)
        if (request.linked) {
            request.linked = false
            try { request.reply.binder.unlinkToDeath(request.death, 0) }
            catch (exception: RuntimeException) { report(exception) }
        }
    }

    private fun report(exception: Exception) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailure >= 5_000L) {
            lastFailure = now
            log(Log.WARN, "SIDEBAR_ENTRIES_FAILED", exception)
        }
    }

    private fun moduleUid(): Int = try {
        service.packageManager.getApplicationInfo(
            SidebarEntryProtocol.MODULE_PACKAGE,
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
        ).uid
    } catch (_: RuntimeException) {
        -1
    }

    private inner class Request(val id: String, val deadline: Long, val reply: Messenger, val target: PinnedTarget.Sidebar?) {
        val active = AtomicBoolean(true)
        var linked = false
        var prepared: ColorOsSidebarEntries? = null
        val timeout = Runnable { finish(this) }
        val death = IBinder.DeathRecipient { handler.post { finish(this) } }
    }
}
