package io.github.mangi.flymefreeform.apps

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import io.github.mangi.flymefreeform.platform.coloros.SidebarEntryProtocol
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SidebarCatalogStatus { Idle, Loading, Ready, Unavailable }
internal data class SidebarCatalogState(
    val entries: List<SelectableEntry> = emptyList(),
    val status: SidebarCatalogStatus = SidebarCatalogStatus.Idle,
)

/** 仅在页面可见时请求；缓存只供显示，目录失败时不允许添加旧条目。 */
internal class SidebarEntryRepository(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val reply = Messenger(Handler(handler.looper, ::receive))
    private val mutableState = MutableStateFlow(SidebarCatalogState())
    val state = mutableState.asStateFlow()
    private var id: String? = null
    private var transfer: CatalogTransfer<SelectableEntry, io.github.mangi.flymefreeform.config.PinnedTarget>? = null
    private val timeout = Runnable { fail() }

    fun refresh() {
        cancel()
        val request = UUID.randomUUID().toString()
        id = request
        transfer = CatalogTransfer(SidebarEntryProtocol.MAX_ENTRIES, SelectableEntry::target)
        mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Loading)
        handler.postDelayed(timeout, SidebarEntryProtocol.TIMEOUT_MS)
        try {
            context.sendBroadcast(Intent(SidebarEntryProtocol.CATALOG_ACTION).setPackage("android").apply {
                putExtra(SidebarEntryProtocol.ID, request)
                putExtra(SidebarEntryProtocol.DEADLINE, SystemClock.uptimeMillis() + SidebarEntryProtocol.TIMEOUT_MS)
                putExtra(SidebarEntryProtocol.REPLY, reply)
            }, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        } catch (_: RuntimeException) { fail() }
    }

    fun cancel() {
        id = null
        transfer?.cancel()
        transfer = null
        handler.removeCallbacks(timeout)
        if (mutableState.value.status == SidebarCatalogStatus.Loading) {
            mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Idle)
        }
    }

    private fun receive(message: Message): Boolean {
        if (id == null || message.sendingUid != Process.SYSTEM_UID || message.arg1 != SidebarEntryProtocol.VERSION) return true
        try {
            val data = message.peekData() ?: return true
            if (data.getString(SidebarEntryProtocol.ID) != id) return true
            when (message.what) {
                SidebarEntryProtocol.ITEM -> {
                    val entry = SidebarEntryProtocol.readEntry(data, (Process.myUid() / 100_000))
                    if (entry == null || transfer?.append(data.getInt(SidebarEntryProtocol.INDEX, -1), entry) != true) fail()
                }
                SidebarEntryProtocol.COMPLETE -> {
                    val entries = transfer?.complete(data.getInt(SidebarEntryProtocol.COUNT, -1))
                    if (entries == null) fail() else {
                        cancel()
                        mutableState.value = SidebarCatalogState(entries, SidebarCatalogStatus.Ready)
                    }
                }
                SidebarEntryProtocol.FAILED -> fail()
            }
        } catch (_: RuntimeException) { fail() }
        return true
    }

    private fun fail() {
        cancel()
        mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Unavailable)
    }
}
