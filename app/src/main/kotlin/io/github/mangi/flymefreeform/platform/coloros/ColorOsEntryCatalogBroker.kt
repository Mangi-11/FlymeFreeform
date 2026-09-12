package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.PinnedTarget
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 设置页只能请求目录；启动能力不通过广播暴露。注册与 system_server 同生命周期。 */
internal class ColorOsEntryCatalogBroker(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
    private val onCatalog: (List<SelectableEntry>) -> Unit,
) {
    private val connection = ColorOsSidebarEntryConnection(context, handler, log)
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "FlymeFreeform-CatalogRelay") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val callbacks = mutableListOf<(List<SelectableEntry>?) -> Unit>()
    private var refreshing = false
    private var launching = false
    private var registered = false
    private var lastRequestAt = -1_000L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SidebarEntryProtocol.CATALOG_ACTION || sentFromPackage != SidebarEntryProtocol.MODULE_PACKAGE || sentFromUid < 0) return
            val sender = sentFromUid
            try {
                val id = intent.getStringExtra(SidebarEntryProtocol.ID) ?: return
                val deadline = intent.getLongExtra(SidebarEntryProtocol.DEADLINE, 0)
                val reply = intent.getParcelableExtra(SidebarEntryProtocol.REPLY, Messenger::class.java) ?: return
                val now = SystemClock.uptimeMillis()
                if (!SidebarProtocol.isValidRequestId(id) || !SidebarProtocol.isValidDeadline(deadline, now, SidebarEntryProtocol.TIMEOUT_MS) ||
                    now - lastRequestAt < 500L
                ) return
                lastRequestAt = now
                worker.execute {
                    try {
                        val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
                        // 系统按签名权限过滤广播后，仍核对实际 UID 与当前用户，拒绝同证书其他包。
                        val userContext = Context::class.java.getMethod("createContextAsUser", android.os.UserHandle::class.java, Int::class.javaPrimitiveType)
                            .invoke(this@ColorOsEntryCatalogBroker.context, android.os.UserHandle.getUserHandleForUid(sender), 0) as Context
                        val expected = userContext.packageManager.getApplicationInfo(
                            SidebarEntryProtocol.MODULE_PACKAGE, PackageManager.ApplicationInfoFlags.of(0),
                        ).uid
                        if (sender != expected || sender / 100_000 != user || SystemClock.uptimeMillis() >= deadline) return@execute
                        handler.post {
                            refresh { entries ->
                                sendCatalog(reply, id, deadline, entries)
                            }
                        }
                    } catch (exception: Exception) { handler.post { log(Log.WARN, "ENTRY_CATALOG_REQUEST_FAILED", null) } }
                }
            } catch (exception: RuntimeException) { log(Log.WARN, "ENTRY_CATALOG_REQUEST_INVALID", null) }
        }
    }

    fun start() {
        if (registered) return
        try {
            context.registerReceiver(receiver, IntentFilter(SidebarEntryProtocol.CATALOG_ACTION),
                SidebarEntryProtocol.CATALOG_PERMISSION, handler, Context.RECEIVER_EXPORTED)
            registered = true
        } catch (exception: RuntimeException) {
            log(Log.WARN, "ENTRY_CATALOG_RECEIVER_UNAVAILABLE", exception)
        }
    }

    fun refresh(onComplete: ((List<SelectableEntry>?) -> Unit)? = null) {
        if (onComplete != null) {
            if (callbacks.size >= 2) { onComplete(null); return }
            callbacks += onComplete
        }
        if (refreshing) return
        refreshing = true
        if (!connection.request { entries, _ -> publish(entries) }) publish(null)
    }

    fun launch(target: PinnedTarget.Sidebar, beforeLaunch: () -> Boolean) {
        connection.cancel()
        launching = true
        if (!connection.request(target, beforeLaunch) { _, dispatched ->
                launching = false
                if (!dispatched) log(Log.WARN, "PINNED_ENTRY_NOT_DISPATCHED", null)
            }) { launching = false; log(Log.WARN, "PINNED_ENTRY_BUSY", null) }
    }

    fun cancelLaunch() { if (launching) connection.cancel() }

    fun cancel() = connection.cancel()

    fun invalidate() {
        connection.cancel()
        onCatalog(emptyList())
    }

    private fun publish(entries: List<SelectableEntry>?) {
        refreshing = false
        if (entries != null) onCatalog(entries)
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it(entries) }
    }

    private fun sendCatalog(reply: Messenger, id: String, deadline: Long, entries: List<SelectableEntry>?) {
        try {
            worker.execute {
                try {
                    if (entries != null) {
                        for ((index, entry) in entries.withIndex()) {
                            if (SystemClock.uptimeMillis() >= deadline) return@execute
                            reply.send(SidebarEntryProtocol.item(id, index, entry))
                        }
                    }
                    if (SystemClock.uptimeMillis() < deadline) reply.send(SidebarEntryProtocol.message(
                        if (entries == null) SidebarEntryProtocol.FAILED else SidebarEntryProtocol.COMPLETE, id,
                    ).apply { data.putInt(SidebarEntryProtocol.COUNT, entries?.size ?: 0) })
                } catch (_: android.os.RemoteException) {
                    // 页面或 App 已离开，剩余目录不再发送。
                    log(Log.DEBUG, "ENTRY_CATALOG_REPLY_DISCONNECTED", null)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            log(Log.WARN, "ENTRY_CATALOG_REPLY_QUEUE_FULL", null)
        }
    }
}
