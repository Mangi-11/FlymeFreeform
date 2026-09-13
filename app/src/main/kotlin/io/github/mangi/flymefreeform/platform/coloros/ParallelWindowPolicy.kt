package io.github.mangi.flymefreeform.platform.coloros

/**
 * 微信「平行小窗」判定：只有前台停在二级界面（朋友圈、视频号、转发等）时才再开一个显示主界面的窗口。
 *
 * 纯逻辑，前台 activity 由调用方读取后传入，便于 JVM 单测。
 * 任何一项拿不准都返回 [Decision.Reuse]：宁可退回原有行为，也不要开错用户。
 *
 * 真机对照结论（ColorOS / Android 16）：原生智能侧边栏在同样的场景下也会把微信拆成两个 task
 * （后台保留朋友圈、小窗显示主界面），划掉小窗后朋友圈仍在、按返回同样直接回桌面。
 * 也就是说"多任务只剩一个微信、返回还能回到主界面"并**不是**原生行为，微信的 `LauncherUI`
 * 是 `singleTop` 单实例，做不到两个独立实例共存。
 *
 * 但原生**只拆一次**：再次打开时复用已有的主界面 task，多任务里始终只有两个。要做到这点，
 * 就不能每次都带 `FLAG_ACTIVITY_MULTIPLE_TASK`（那会每点一次多一个 task），只有叠放状态需要它。
 */
internal object ParallelWindowPolicy {
    /** 与原生对齐的行为已通过真机验证，可以开启。 */
    const val ENABLED = true

    /** 目前只放开微信：它的主界面可以被重新拉起成第二个窗口。 */
    const val TARGET_PACKAGE = "com.tencent.mm"

    /**
     * 聊天窗口：`com.tencent.mm.ui.chatting.ChattingUI`（含历史版本的后缀变体）才算一级体验。
     * 只匹配 `chatting.ChattingUI` 而不是整段 `chatting`，避免把转发、选择联系人等
     * 同样挂在 `ui.chatting.*` 下的页面也算成主界面。
     */
    private const val CHATTING_WINDOW_SEGMENT = "chatting.ChattingUI"

    internal enum class Decision {
        /** 普通启动，与功能开启前一致。 */
        Normal,

        /** 二级界面已独立成 task：带小窗协议复用已有的主界面 task，不新建、不累积。 */
        FlexibleReuse,

        /** 二级界面还叠在主界面上：普通启动会被系统提到前台，必须新开一个实例。 */
        FlexibleNewInstance,
    }

    fun isTarget(packageName: String): Boolean = packageName == TARGET_PACKAGE

    /**
     * 用**白名单**判定主界面：只有启动组件本身（聊天列表）与聊天窗口算主界面，
     * 其余页面一律算二级界面。早先按 `.plugin.` 黑名单判断，会把转发、搜索等
     * 不挂在插件包下的页面误判成主界面，导致这些页面打不开平行小窗。
     */
    fun isMainSurface(
        launcherClassName: String,
        topClassName: String,
    ): Boolean =
        topClassName == launcherClassName ||
            topClassName.contains(CHATTING_WINDOW_SEGMENT, ignoreCase = true)

    fun decide(
        targetPackage: String,
        targetUserId: Int,
        targetLauncherClass: String,
        focusedPackage: String?,
        focusedUserId: Int?,
        focusedClassName: String?,
        focusedTaskHasLauncher: Boolean,
    ): Decision {
        if (!isTarget(targetPackage)) return Decision.Normal
        if (focusedPackage != targetPackage) return Decision.Normal
        // 前台用户必须与目标用户一致：主应用与分身同包名，只靠包名无法区分。
        if (focusedUserId == null || focusedUserId != targetUserId) return Decision.Normal
        val topClassName = focusedClassName ?: return Decision.Normal
        if (isMainSurface(targetLauncherClass, topClassName)) return Decision.Normal
        return if (focusedTaskHasLauncher) Decision.FlexibleNewInstance else Decision.FlexibleReuse
    }
}
