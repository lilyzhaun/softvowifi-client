@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallProgressAudioTest {
    @Test fun ringingPlaysBeforeAnswerAndStopsOnTerminalEvent() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            wire.emitCallState(CALL_TOKEN, "DIALING")
            assertEquals(0, audio.starts.size)
            wire.emitProgress(CALL_TOKEN, ringback = true)
            assertEquals(1, audio.starts.size, "180 must render local ringback while dial remains pending")
            assertEquals(CallAudioMode.Progress, audio.starts.single().mode)
            assertTrue(audio.starts.single().ringback)
            assertEquals(CallPhase.Dialing, assertIs<CallUiState.Ready>(source.state.value).phase)
            wire.emitCallEnded(CALL_TOKEN, "failed")
            assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertTrue(audio.stopCalls > 0)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun earlyAnnouncementPlaybackDoesNotClaimAnsweredAndPromotesOnce() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            wire.emitProgress(CALL_TOKEN, ringback = false, earlyMediaRevision = 1)
            assertEquals(1, audio.starts.size, "183 announcement must play without waiting for 200")
            val waiting = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.Dialing, waiting.phase)
            assertNull(waiting.clock)
            assertFalse(waiting.dtmfSupported)
            assertTrue(waiting.dialBusy)
            wire.emitProgress(CALL_TOKEN, ringback = false, earlyMediaRevision = 1)
            assertEquals(1, audio.starts.size, "duplicate early progress must not restart playback")
            wire.emitCallState(CALL_TOKEN, "IN_CALL")
            gate.complete(Unit)
            assertEquals(2, audio.starts.size, "confirmed capture replaces receive-only progress once")
            assertEquals(listOf(CallAudioMode.Progress, CallAudioMode.Conversation), audio.starts.map { it.mode })
            assertEquals(1L, audio.starts.first().earlyMediaRevision)
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun cancelStopsProgressBeforeTheControlRequestReturns() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial", "cancelCall"))
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            wire.emitProgress(CALL_TOKEN, ringback = true, earlyMediaRevision = 1)
            assertEquals(1, audio.starts.size)
            source.cancel()
            assertEquals(CallPhase.Canceling, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertTrue(audio.stopCalls > 0, "cancel must not leave ringback/announcement playing through network drain")
            assertNull(audio.activeToken)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun subsequent180UpdatesExistingEarlyPlaybackAndDoesNotWaitForDial() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            wire.emitProgress(CALL_TOKEN, ringback = false, earlyMediaRevision = 1)
            wire.emitProgress(CALL_TOKEN, ringback = true, earlyMediaRevision = 1)
            assertEquals(2, audio.starts.size)
            assertTrue(audio.starts.last().ringback)
            assertEquals(CallAudioMode.Progress, audio.starts.last().mode)
            assertEquals(1L, audio.starts.last().earlyMediaRevision)
            assertEquals(CallPhase.Dialing, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertFalse(gate.isCompleted)
            wire.emitProgress(CALL_TOKEN, ringback = true, earlyMediaRevision = 1)
            assertEquals(2, audio.starts.size)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun earlyPCMEOFBeforeFinalEventDoesNotInvalidateThePreparedConversation() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            wire.emitProgress(CALL_TOKEN, ringback = false, earlyMediaRevision = 1)
            audio.emitEnded()
            val transitioning = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.Dialing, transitioning.phase)
            assertFalse(transitioning.audio is CallAudioState.Failed,
                "正常早期PCM替换EOF不能触发AndroidCallLifecycle撤销整通电话的麦克风授权")
            assertTrue(transitioning.dialBusy)
            wire.emitCallState(CALL_TOKEN, "IN_CALL")
            gate.complete(Unit)
            val confirmed = assertIs<CallUiState.Ready>(source.state.value)
            assertEquals(CallPhase.InCall, confirmed.phase)
            assertEquals(CallAudioState.Active, confirmed.audio)
            assertEquals(2, audio.starts.size)
        } finally { source.close(); gate.complete(Unit) }
    }
}
