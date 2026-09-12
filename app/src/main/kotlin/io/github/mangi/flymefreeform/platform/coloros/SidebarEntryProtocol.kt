package io.github.mangi.flymefreeform.platform.coloros

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import io.github.mangi.flymefreeform.apps.SelectableEntry
import io.github.mangi.flymefreeform.config.PinnedTarget
import io.github.mangi.flymefreeform.config.PinnedTargetCodec

/** 目录传输逐项限长，只有完整且有序的会话才发布；请求中不接受 Intent。 */
internal object SidebarEntryProtocol {
    const val MODULE_PACKAGE = "io.github.mangi.flymefreeform"
    const val CATALOG_ACTION = "$MODULE_PACKAGE.action.REQUEST_ENTRY_CATALOG"
    const val CATALOG_PERMISSION = "$MODULE_PACKAGE.permission.REQUEST_ENTRY_CATALOG"
    const val BIND_ACTION = "$MODULE_PACKAGE.action.BIND_ENTRIES_V1"
    const val VERSION = 1
    const val LIST = 1
    const val LAUNCH = 2
    const val CANCEL = 3
    const val EXECUTE = 4
    const val PREPARED = 14
    const val ITEM = 10
    const val COMPLETE = 11
    const val DISPATCHED = 12
    const val FAILED = 13
    const val ID = "id"
    const val DEADLINE = "deadline"
    const val REPLY = "reply"
    const val TARGET = "target"
    const val INDEX = "index"
    const val COUNT = "count"
    private const val LABEL = "label"
    private const val PACKAGE = "package"
    private const val ICON = "icon"
    const val TIMEOUT_MS = 7_000L
    const val MAX_ENTRIES = 256
    const val ICON_SIZE = 96

    fun message(what: Int, id: String, deadline: Long = 0L, reply: Messenger? = null): Message =
        Message.obtain().apply {
            this.what = what
            arg1 = VERSION
            replyTo = reply
            data = Bundle().apply { putString(ID, id); putLong(DEADLINE, deadline) }
        }

    fun item(id: String, index: Int, entry: SelectableEntry): Message = message(ITEM, id).apply {
        data.putInt(INDEX, index)
        data.putString(TARGET, PinnedTargetCodec.encode(entry.target))
        data.putString(LABEL, entry.label.take(160))
        data.putString(PACKAGE, entry.packageName.take(256))
        data.putParcelable(ICON, entry.icon)
    }

    fun readEntry(data: Bundle, userId: Int): SelectableEntry? {
        val target = data.getString(TARGET)?.let(PinnedTargetCodec::decode) as? PinnedTarget.Sidebar ?: return null
        if (target.userId != userId) return null
        val label = data.getString(LABEL)?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return null
        val pkg = data.getString(PACKAGE)?.takeIf { it.length <= 256 } ?: return null
        val icon = data.getParcelable(ICON, Bitmap::class.java)?.takeIf {
            !it.isRecycled && it.width in 1..ICON_SIZE && it.height in 1..ICON_SIZE &&
                it.allocationByteCount <= ICON_SIZE * ICON_SIZE * 4
        } ?: return null
        return SelectableEntry(target, label, pkg, icon)
    }
}
