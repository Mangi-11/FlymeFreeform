package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.window.MorePanelSession.Effect
import io.github.mangi.flymefreeform.platform.coloros.SidebarProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class MorePanelSessionTest {
    @Test
    fun coldStartHandshakeDoesNotConsumeThePanelOpeningBudget() {
        val startedAt = 1_000L
        val readyAt = startedAt + 6_000L
        val session = MorePanelSession("cold", startedAt + SidebarProtocol.PREPARE_TIMEOUT_MS)
        assertEquals(Effect.Open, session.ready("cold", readyAt, readyAt + SidebarProtocol.OPEN_TIMEOUT_MS))
        assertEquals(Effect.Confirm, session.shown("cold", readyAt + 2_000L))
        assertEquals(Effect.Shown, session.committed("cold"))
    }

    @Test
    fun lateColdStartReplyCannotOpenAfterUserCancellation() {
        val session = MorePanelSession("cold", SidebarProtocol.PREPARE_TIMEOUT_MS)
        assertEquals(Effect.Abandon, session.cancel(allowFallback = false))
        assertEquals(Effect.None, session.ready("cold", 6_000L, 9_000L))
        assertEquals(Effect.None, session.shown("cold", 6_500L))
    }

    @Test
    fun nativePanelRequiresBothDisplayAndCommitConfirmation() {
        val session = MorePanelSession("request", 100)
        assertEquals(Effect.Open, session.ready("request", 10, 200))
        assertEquals(Effect.Confirm, session.shown("request", 50))
        assertEquals(MorePanelSession.Phase.Confirming, session.phase)
        assertEquals(Effect.Shown, session.committed("request"))
        assertEquals(Effect.None, session.cleaned("request"))
    }

    @Test
    fun duplicateMessagesNeverRepeatOpenOrConfirm() {
        val session = MorePanelSession("request", 100)
        assertEquals(Effect.Open, session.ready("request", 10, 200))
        assertEquals(Effect.None, session.ready("request", 11, 201))
        assertEquals(Effect.Confirm, session.shown("request", 20))
        assertEquals(Effect.None, session.shown("request", 21))
        assertEquals(Effect.Shown, session.committed("request"))
        assertEquals(Effect.None, session.committed("request"))
    }

    @Test
    fun preparationTimeoutCanFallBackWithoutOpeningNativeUi() {
        val session = MorePanelSession("request", 100)
        assertEquals(Effect.Fallback, session.ready("request", 100, 200))
        assertEquals(Effect.None, session.ready("request", 101, 201))
    }

    @Test
    fun openingTimeoutRequiresCleanupBeforeFallback() {
        val session = MorePanelSession("request", 100)
        session.ready("request", 10, 200)
        assertEquals(Effect.Cancel, session.timeout())
        assertEquals(MorePanelSession.Phase.Cancelling, session.phase)
        assertEquals(Effect.None, session.shown("request", 220))
        assertEquals(Effect.Fallback, session.cleaned("request"))
        assertEquals(Effect.None, session.cleaned("request"))
    }

    @Test
    fun uncertainCleanupNeverOpensASecondPanel() {
        val session = MorePanelSession("request", 100)
        session.ready("request", 10, 200)
        assertEquals(Effect.Cancel, session.timeout())
        assertEquals(Effect.Abandon, session.timeout())
        assertEquals(Effect.None, session.cleaned("request"))
    }

    @Test
    fun lateShownRequestIsCancelledInsteadOfConfirmed() {
        val session = MorePanelSession("request", 100)
        session.ready("request", 10, 200)
        assertEquals(Effect.Cancel, session.shown("request", 200))
        assertEquals(Effect.Fallback, session.cleaned("request"))
    }

    @Test
    fun modeSwitchOrEnvironmentChangeCancelsWithoutFallback() {
        val preparing = MorePanelSession("preparing", 100)
        assertEquals(Effect.Abandon, preparing.cancel(allowFallback = false))

        val opening = MorePanelSession("opening", 100)
        opening.ready("opening", 10, 200)
        assertEquals(Effect.Cancel, opening.cancel(allowFallback = false))
        assertEquals(Effect.Abandon, opening.cleaned("opening"))
    }

    @Test
    fun environmentChangeDuringTimeoutCleanupSuppressesFallback() {
        val session = MorePanelSession("request", 100)
        session.ready("request", 10, 200)
        session.timeout()
        assertEquals(Effect.None, session.cancel(allowFallback = false))
        assertEquals(Effect.Abandon, session.cleaned("request"))
    }

    @Test
    fun alreadyCommittedNativeUiWinsTheCancelRace() {
        val session = MorePanelSession("request", 100)
        session.ready("request", 10, 200)
        session.shown("request", 20)
        session.cancel(allowFallback = true)
        assertEquals(Effect.Shown, session.committed("request"))
        assertEquals(Effect.None, session.cleaned("request"))
    }

    @Test
    fun previousRequestRepliesCannotAffectANewRequest() {
        val session = MorePanelSession("new", 100)
        assertEquals(Effect.None, session.ready("old", 10, 200))
        assertEquals(Effect.None, session.shown("old", 20))
        assertEquals(Effect.None, session.committed("old"))
        assertEquals(Effect.None, session.cleaned("old"))
        assertEquals(Effect.None, session.abort("old"))
        assertEquals(MorePanelSession.Phase.Preparing, session.phase)
    }
}
