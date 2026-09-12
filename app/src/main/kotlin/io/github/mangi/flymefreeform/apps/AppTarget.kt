package io.github.mangi.flymefreeform.apps

import android.content.Context
import android.content.ComponentName
import android.os.Process
import android.os.UserHandle

/**
 * 分身后缀必须是常量：目录枚举运行在 `system_server` 内，那里只能拿到系统资源，
 * 引用模块自身的 `R.string` 会抛 `Resources.NotFoundException`，让整条分身条目被静默丢弃。
 */
internal const val CLONE_LABEL_SUFFIX = "应用分身"

/** 同一包名可能同时存在于主用户和应用分身用户中，因此启动目标必须包含 userId。 */
internal data class AppTarget(
    val component: ComponentName,
    val userId: Int,
) {
    val user: UserHandle
        get() = userHandleOf(userId)

    val storageKey: String
        get() = "${component.flattenToString()}#$userId"

    val isClone: Boolean
        get() = userId != MAIN_USER_ID

    /** @param baseLabel 主用户下的原始名称，分身会在其后追加分身后缀。 */
    fun displayLabel(baseLabel: String): String =
        if (isClone) "$baseLabel · $CLONE_LABEL_SUFFIX" else baseLabel

    companion object {
        const val MAIN_USER_ID = 0
    }
}

internal val UserHandle.identifier: Int
    get() =
        UserHandle::class.java
            .getMethod("getIdentifier")
            .invoke(this) as Int

internal fun userHandleOf(identifier: Int): UserHandle =
    if (identifier == Process.myUserHandle().identifier) {
        Process.myUserHandle()
    } else {
        UserHandle::class.java
            .getMethod("of", Int::class.javaPrimitiveType)
            .invoke(null, identifier) as UserHandle
    }

internal fun Context.createContextForUser(identifier: Int): Context =
    if (identifier == Process.myUserHandle().identifier) {
        this
    } else {
        Context::class.java
            .getMethod(
                "createContextAsUser",
                UserHandle::class.java,
                Int::class.javaPrimitiveType,
            )
            .invoke(this, userHandleOf(identifier), 0) as Context
    }
