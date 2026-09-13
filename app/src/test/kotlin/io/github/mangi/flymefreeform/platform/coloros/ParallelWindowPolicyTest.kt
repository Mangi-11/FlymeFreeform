package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelWindowPolicyTest {
    private val launcher = "com.tencent.mm.ui.LauncherUI"
    private val moments = "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
    private val chatting = "com.tencent.mm.ui.chatting.ChattingUI"

    private fun decide(
        topPackage: String? = ParallelWindowPolicy.TARGET_PACKAGE,
        topUser: Int? = 0,
        topClass: String? = moments,
        targetUser: Int = 0,
        targetPackage: String = ParallelWindowPolicy.TARGET_PACKAGE,
        taskHasLauncher: Boolean = true,
    ) = ParallelWindowPolicy.decide(
        targetPackage = targetPackage,
        targetUserId = targetUser,
        targetLauncherClass = launcher,
        focusedPackage = topPackage,
        focusedUserId = topUser,
        focusedClassName = topClass,
        focusedTaskHasLauncher = taskHasLauncher,
    )

    @Test
    fun opensNewInstanceWhenSecondLevelPageIsStackedOnTopOfLauncher() {
        assertEquals(ParallelWindowPolicy.Decision.FlexibleNewInstance, decide(taskHasLauncher = true))
    }

    @Test
    fun reusesExistingTaskWhenSecondLevelPageIsAlreadyStandalone() {
        // 已经拆开（朋友圈独立成 task）时带小窗协议复用主界面 task，不能再新开实例。
        assertEquals(ParallelWindowPolicy.Decision.FlexibleReuse, decide(taskHasLauncher = false))
    }

    @Test
    fun keepsExistingWindowOnMainSurfaces() {
        assertEquals(ParallelWindowPolicy.Decision.Normal, decide(topClass = launcher))
        assertEquals(ParallelWindowPolicy.Decision.Normal, decide(topClass = chatting))
    }

    @Test
    fun neverMixesPrimaryUserAndClone() {
        assertEquals(ParallelWindowPolicy.Decision.Normal, decide(topUser = 0, targetUser = 999))
        assertEquals(ParallelWindowPolicy.Decision.Normal, decide(topUser = 999, targetUser = 0))
        assertEquals(ParallelWindowPolicy.Decision.Normal, decide(topUser = null))
        assertEquals(
            ParallelWindowPolicy.Decision.FlexibleNewInstance,
            decide(topUser = 999, targetUser = 999),
        )
    }

    @Test
    fun allowsOpeningAgainAfterTheWindowWasClosed() {
        // 关掉小窗后朋友圈又变回独立 task，此时走复用；重新叠放后仍能再新开一次。
        assertEquals(ParallelWindowPolicy.Decision.FlexibleReuse, decide(taskHasLauncher = false))
        assertEquals(ParallelWindowPolicy.Decision.FlexibleNewInstance, decide(taskHasLauncher = true))
    }

    @Test
    fun ignoresOtherPackages() {
        assertEquals(ParallelWindowPolicy.Decision.Normal, decide(topPackage = "com.tencent.mobileqq"))
        assertEquals(
            ParallelWindowPolicy.Decision.Normal,
            decide(targetPackage = "com.tencent.mobileqq"),
        )
    }

    @Test
    fun classifiesMainSurfaces() {
        assertTrue(ParallelWindowPolicy.isMainSurface(launcher, launcher))
        assertTrue(ParallelWindowPolicy.isMainSurface(launcher, chatting))
        assertFalse(ParallelWindowPolicy.isMainSurface(launcher, moments))
        assertFalse(
            ParallelWindowPolicy.isMainSurface(
                launcher,
                "com.tencent.mm.plugin.finder.ui.FinderHomeUI",
            ),
        )
    }

    @Test
    fun treatsNonLauncherNonChatPagesAsSecondLevel() {
        // 白名单判定：转发、搜索等不挂在插件包下的页面同样是二级界面，也要能开平行小窗。
        assertFalse(
            ParallelWindowPolicy.isMainSurface(
                launcher,
                "com.tencent.mm.ui.transmit.SelectConversationUI",
            ),
        )
        assertFalse(
            ParallelWindowPolicy.isMainSurface(
                launcher,
                "com.tencent.mm.ui.chatting.SelectConversationUI",
            ),
        )
        assertFalse(
            ParallelWindowPolicy.isMainSurface(
                launcher,
                "com.tencent.mm.plugin.brandservice.ui.timeline.preload.ui.TmplWebViewMMUI",
            ),
        )
        // 对应地，这些页面会走平行小窗而不是普通启动。
        assertEquals(
            ParallelWindowPolicy.Decision.FlexibleReuse,
            decide(
                topClass = "com.tencent.mm.ui.transmit.SelectConversationUI",
                taskHasLauncher = false,
            ),
        )
    }
}
