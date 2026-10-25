package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlin.test.*

class CommunicationRegistrationNoticeTest {
    private val idle = CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE)
    private val line = CallLineChoice(OWNER_A, "合成线路", true, true, false, false, true, running = true)

    @Test fun onlyCompleteKnownUnregisteredIdleFactsCanOfferTheHomeHint() {
        val lines = CallLineState(listOf(line), rosterComplete = true)
        assertTrue(confirmedNoRegisteredCommunicationLine(lines, idle))
        assertFalse(confirmedNoRegisteredCommunicationLine(lines.copy(rosterComplete = false), idle))
        assertFalse(confirmedNoRegisteredCommunicationLine(lines.copy(choices = listOf(line.copy(registered = null))), idle))
        assertFalse(confirmedNoRegisteredCommunicationLine(lines.copy(choices = listOf(line.copy(registered = true))), idle))
        for (phase in CallPhase.entries.filter { it != CallPhase.Idle })
            assertFalse(confirmedNoRegisteredCommunicationLine(lines, idle.copy(phase = phase)))
        assertFalse(confirmedNoRegisteredCommunicationLine(lines, CallUiState.Loading))
        assertFalse(confirmedNoRegisteredCommunicationLine(lines, CallUiState.Unavailable("合成未知状态")))
        assertTrue(confirmedNoRegisteredCommunicationLine(lines.copy(choices = listOf(line.copy(running = false, registered = null))), idle))
        assertTrue(confirmedNoRegisteredCommunicationLine(lines.copy(choices = emptyList()), idle))
    }
}
