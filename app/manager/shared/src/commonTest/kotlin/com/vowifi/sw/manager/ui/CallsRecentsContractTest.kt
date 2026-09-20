package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallPeerPhone
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CallsRecentsContractTest {
    @Test
    fun historyGroupsNewestFirstUsingLocalDay() {
        val records = listOf(record(1), record(3), record(2))

        val groups = callHistoryDays(records) { if (it < 3) "earlier" else "today" }

        assertEquals(listOf("today", "earlier"), groups.map { it.label })
        assertEquals(listOf(3L, 2L, 1L), groups.flatMap { it.records }.map { it.startedAtMs })
    }

    @Test
    fun keypadEditsOneBoundedDraft() {
        val empty = ""

        val plus = editCallDraft(empty, DialKey.PLUS)

        assertEquals("+", plus)
        assertEquals("+1", editCallDraft(plus, DialKey.ONE))
        assertEquals("1", editCallDraft("1", DialKey.PLUS))
        assertEquals("", editCallDraft("1", DialKey.BACKSPACE))
        assertEquals("", editCallDraft("", DialKey.BACKSPACE))
        assertEquals("1".repeat(20), editCallDraft("1".repeat(20), DialKey.TWO))
        assertEquals("12", acceptCallDraft("12", "sip:bad"))
    }

    @Test
    fun dialingLeavesDialpadAndIncomingClearsNestedRoute() {
        val root = AppNavigationState.initial(AppRoute.Calls)
        val dialpad = AppNavigator.push(root, AppRoute.Dialpad)

        val dialing = AppNavigator.forCallTransition(dialpad, CallPhase.Idle, CallPhase.Dialing)

        assertEquals(listOf(AppRoute.Calls), dialing.backStack)
        assertEquals(listOf(AppRoute.Calls), AppNavigator.forCallTransition(dialpad, CallPhase.Idle, CallPhase.Ringing).backStack)
        assertEquals(root, AppNavigator.back(dialpad))
        assertSame(root, AppNavigator.forCallTransition(root, CallPhase.InCall, CallPhase.InCall))
    }

    private fun record(time: Long) = CallRecord(
        CallDirection.INCOMING, null, time, null, time, null, CallOutcome.MISSED,
    )

    @Test
    fun activeIdentityComesOnlyFromCurrentSession() {
        val dialing = CallUiState.Ready(CallPhase.Dialing, CallAudioState.Idle, CallOrigin.ENGINE)
        assertEquals("未知号码", activeCallPeerDisplay(dialing))
        assertEquals("未知号码", activeCallPeerDisplay(dialing.copy(phase = CallPhase.Ringing)))
        val known = dialing.copy(peer = com.vowifi.sw.manager.model.CallPeer.parse("+12025550123"))
        assertEquals(privateUiPhoneDisplay("+12025550123"), activeCallPeerDisplay(known))
    }
}
