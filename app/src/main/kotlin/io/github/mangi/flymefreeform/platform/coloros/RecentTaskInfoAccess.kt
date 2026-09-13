package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.os.Process
import io.github.mangi.flymefreeform.apps.identifier

/**
 * `RecentTaskInfo.userId` 不是公开字段：主用户与分身用户的 task 只能反射区分。
 * 读不到时按当前用户处理，避免把任务错误归属到另一个用户。
 */
internal fun ActivityManager.RecentTaskInfo.taskUserIdentifier(): Int =
    try {
        javaClass
            .getDeclaredField("userId")
            .also { it.isAccessible = true }
            .getInt(this)
    } catch (_: ReflectiveOperationException) {
        Process.myUserHandle().identifier
    } catch (_: RuntimeException) {
        Process.myUserHandle().identifier
    }
