package io.github.mangi.flymefreeform.platform.coloros

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import java.util.UUID

/** 跨进程只传框架类型，不把系统视图、配置快照或应用列表放入协议。 */
internal object SidebarProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "android.os.IMessenger"
    const val PREPARE = 1
    const val OPEN = 2
    const val CONFIRM = 3
    const val CANCEL = 4
    const val READY = 10
    const val SHOWN = 11
    const val COMMITTED = 12
    const val CLEANED = 13
    const val ABORTED = 14
    const val EXIT_STARTED = 15
    const val HIDE_BACKDROP = 16
    const val BACKDROP_HIDDEN = 17
    const val REQUEST_ID = "request_id"
    const val DEADLINE = "deadline_uptime"
    const val TARGET_UID = "target_uid"
    // 原生常驻端允许绑定等待 5 秒，额外留出框架加载与桥接握手时间。
    const val PREPARE_TIMEOUT_MS = 7_000L
    const val OPEN_TIMEOUT_MS = 3_000L
    const val CLEANUP_TIMEOUT_MS = 1_500L
    const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"

    fun isTrustedPeer(sendingUid: Int, expectedUid: Int, version: Int): Boolean =
        expectedUid >= 0 && sendingUid == expectedUid && version == VERSION

    fun isValidRequestId(id: String): Boolean =
        id.length == 36 &&
            try {
                UUID.fromString(id).toString() == id
            } catch (_: IllegalArgumentException) {
                false
            }

    fun isValidDeadline(deadline: Long, now: Long, maximumLifetime: Long): Boolean =
        now >= 0 && deadline > now && deadline - now <= maximumLifetime

    fun message(
        what: Int,
        id: String,
        deadline: Long,
        targetUid: Int,
        replyTo: Messenger? = null,
    ): Message =
        Message.obtain().apply {
            this.what = what
            arg1 = VERSION
            this.replyTo = replyTo
            data =
                Bundle().apply {
                    putString(REQUEST_ID, id)
                    putLong(DEADLINE, deadline)
                    putInt(TARGET_UID, targetUid)
                }
        }
}
