package io.github.mangi.flymefreeform.window

import io.github.mangi.flymefreeform.window.AllAppsActionHandoff.Action
import org.junit.Assert.*
import org.junit.Test

class AllAppsActionHandoffTest {
    @Test fun appStartsDuringExitAndIsNotLaunchedAgainAtItsEnd() {
        val handoff = AllAppsActionHandoff()
        assertEquals(Action.ExecuteNow, handoff.select(tool = false))
        assertEquals(Action.Ignore, handoff.select(tool = false))
        assertFalse(handoff.hidden())
    }

    @Test fun screenshotWaitsForTheHiddenFrameAndExecutesOnlyOnce() {
        val handoff = AllAppsActionHandoff()
        assertEquals(Action.ExecuteWhenHidden, handoff.select(tool = true))
        assertEquals(Action.Ignore, handoff.select(tool = true))
        assertTrue(handoff.hidden())
        assertFalse(handoff.hidden())
    }

    @Test fun lockOrUserSwitchDuringExitCancelsThePendingTool() {
        val handoff = AllAppsActionHandoff()
        handoff.select(tool = true)
        handoff.cancel()
        assertFalse(handoff.hidden())
        assertEquals(Action.Ignore, handoff.select(tool = false))
    }
}
