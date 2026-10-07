@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallEndToneTest {
    @Test fun matchedBusinessTerminalPlaysOnlyOnceAfterProgress() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial"))
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            wire.emitProgress(CALL_TOKEN, true, 1)
            wire.emitCallEnded(CALL_TOKEN, "failed")
            assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(source.state.value).phase)
            assertEquals(1, audio.endTones.size, "匹配网络终态必须产生一次有限结束反馈")
            assertEquals(CALL_TOKEN, audio.endTones.single().token)
            wire.emitCallEnded(CALL_TOKEN, "failed")
            assertEquals(1, audio.endTones.size)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun normalHangupFeedbackDoesNotRepeatAfterTheLateEndedEvent() = runTest(UnconfinedTestDispatcher()) {
        val wire = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(source.state.value).phase)
            source.hangup()
            assertEquals(1, audio.endTones.size)
            wire.emitCallEnded(CALL_TOKEN, "hangup")
            assertEquals(1, audio.endTones.size)
        } finally { source.close() }
    }

    @Test fun lifecycleCloseAndStaleTerminalRemainSilent() = runTest(UnconfinedTestDispatcher()) {
        val wire = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        source.dial(CALL_NUMBER)
        wire.emitCallEnded("stale-synthetic-token", "remote")
        assertEquals(0, audio.endTones.size)
        source.close()
        assertEquals(0, audio.endTones.size)
    }

    @Test fun anotherScopeCannotEndTheCurrentAudioAndDuplicateEndDoesNotPlayAgain() = runTest {
        val native = FakeCallAudioController()
        val audio = CallSessionAudio(native)
        val first = audio.child(); val second = audio.child()
        val wire = FakeCallTransport()
        val initial = CallAudioSession(CALL_ICCID_A, CALL_TOKEN, 1, wire)
        val next = CallAudioSession(CALL_ICCID_A, "next-synthetic-token", 2, wire)
        try {
            first.start(initial)
            second.callEnded(initial)
            assertEquals(0, native.endTones.size)
            first.stopIf(initial)
            second.start(next)
            first.callEnded(initial)
            assertEquals(0, native.endTones.size)
            second.callEnded(next)
            second.callEnded(next)
            assertEquals(listOf(next), native.endTones)
        } finally { first.retire(); second.retire(); audio.close() }
    }

    @Test fun cancelStopsProgressImmediatelyAndPlaysOnlyAfterBusinessTerminal() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val wire = FakeCallTransport(requestGate = gate, gatedMethods = setOf("dial", "cancelCall"))
        val native = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = native)
        try {
            source.dial(CALL_NUMBER)
            wire.emitProgress(CALL_TOKEN, true, 1)
            source.cancel()
            assertNull(native.activeToken)
            assertEquals(0, native.endTones.size)
            wire.emitCallEnded(CALL_TOKEN, "canceled")
            assertEquals(1, native.endTones.size)
        } finally { source.close(); gate.complete(Unit) }
    }

    @Test fun failedOrLostAudioIsNotTakenBackForAnEndTone() = runTest(UnconfinedTestDispatcher()) {
        val wire = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio = audio)
        try {
            source.dial(CALL_NUMBER)
            audio.emitEnded()
            assertIs<CallAudioState.Failed>(assertIs<CallUiState.Ready>(source.state.value).audio)
            wire.emitCallEnded(CALL_TOKEN, "failed")
            assertEquals(0, audio.endTones.size)
        } finally { source.close() }
    }

    @Test fun terminalFeedbackKeepsCallIdentityWhenEndingSnapshotHasNoProgressRole() = runTest {
        val native = FakeCallAudioController()
        val audio = CallSessionAudio(native)
        val child = audio.child()
        val wire = FakeCallTransport()
        val progress = CallAudioSession(CALL_ICCID_A, CALL_TOKEN, 1, wire, CallAudioMode.Progress, 1, true)
        try {
            child.start(progress)
            child.stopIf(progress)
            child.callEnded(progress.copy(mode = CallAudioMode.Conversation, earlyMediaRevision = 0, ringback = false))
            assertEquals(1, native.endTones.size)
        } finally { child.retire(); audio.close() }
    }
}
