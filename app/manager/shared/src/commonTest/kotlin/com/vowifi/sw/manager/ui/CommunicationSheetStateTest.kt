package com.vowifi.sw.manager.ui

import kotlin.test.*

class CommunicationSheetStateTest {
    @Test fun firstCloseWinsAndOnlyFinishingTheExitExecutesIt() {
        val state = CommunicationSheetState()
        val calls = mutableListOf<String>()
        state.close { calls += "select" }
        state.close { calls += "edit" }
        assertFalse(state.visible)
        assertTrue(calls.isEmpty())
        state.finishDismiss()
        state.finishDismiss()
        assertEquals(listOf("select"), calls)
    }

    @Test fun routeInvalidationReplacesPendingActionsWithCleanup() {
        val state = CommunicationSheetState()
        val calls = mutableListOf<String>()
        state.close { calls += "edit" }
        state.cancelPending { calls += "dismiss" }
        state.finishDismiss()
        assertEquals(listOf("dismiss"), calls)
        assertFalse(state.visible)
    }
}
