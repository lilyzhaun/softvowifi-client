@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallSourceConnectTest {
    @Test
    fun attachingSingleRunningEngineConnectsWithoutRequest() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)

        assertEquals(1, transport.connectCalls)
        assertTrue(transport.requests.isEmpty())
        assertIs<CallUiState.Ready>(source.state.value)
        assertNoGetStatus(transport)
        source.close()
    }

    @Test
    fun zeroRunningInstancesDoNotConnect() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callStopped(CALL_ICCID_A)), transport)

        assertEquals(0, transport.connectCalls)
        assertTrue(transport.requests.isEmpty())
        assertIs<CallUiState.Unavailable>(source.state.value)
        source.close()
    }

    @Test
    fun connectFailureSurfacesFailureState() = runTest {
        val transport = FakeCallTransport(connectHandler = { error("socket refused sip:bob@example.com") })
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        testScheduler.advanceUntilIdle()

        val failure = assertIs<CallUiState.Failure>(source.state.value)
        assertEquals(CallFailureMessages.CONNECT, failure.message)
        assertEquals(1, transport.connectCalls)
        assertTrue(transport.requests.isEmpty())
        assertCallPrivacy(failure, "socket refused", "sip:bob@example.com")
        source.close()
    }

    @Test
    fun eventEmittedDuringConnectIsRetainedAsRinging() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(
            connectHandler = { emitIncoming(CALL_TOKEN, "RINGING") },
        )
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)

        val ready = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Ringing, ready.phase)
        assertCallPrivacy(ready, CALL_TOKEN)
        source.close()
    }

    @Test
    fun zeroRunningInstancesStayUnavailableAndIgnoreActions() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callStopped(CALL_ICCID_A)), transport, audio)

        assertIs<CallUiState.Unavailable>(source.state.value)
        source.answer()
        source.reject()
        source.hangup()
        assertTrue(transport.requests.isEmpty())
        assertEquals(0, audio.starts.size)
        assertNoGetStatus(transport)
        source.close()
    }

    @Test
    fun multipleRunningInstancesStayUnsupported() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A), callRunning(CALL_ICCID_B)), transport)

        val failure = assertIs<CallUiState.Failure>(source.state.value)
        assertEquals(ActiveEngineSelector.MULTIPLE_MESSAGE, failure.message)
        source.answer()
        assertTrue(transport.requests.isEmpty())
        source.close()
    }

    @Test
    fun permissionDeniedUsesBoundedMessage() = runTest(UnconfinedTestDispatcher()) {
        val source = EngineCallSource(
            runtime = EngineMessageRuntime(
                listInstances = { throw MessagePermissionException("EACCES /proc/1") },
                transportFor = { FakeCallTransport() },
                clock = { 0L },
            ),
            audio = FakeCallAudioController(),
            control = CallControlTransport { _, _, _, _ -> error("unexpected control request") },
            parentContext = coroutineContext,
        )

        val denied = assertIs<CallUiState.PermissionDenied>(source.state.value)
        assertEquals(CallFailureMessages.PERMISSION, denied.message)
        assertCallPrivacy(denied)
        source.close()
    }

    @Test
    fun listFailureUsesBoundedMessageWithoutRawError() = runTest(UnconfinedTestDispatcher()) {
        val source = EngineCallSource(
            runtime = EngineMessageRuntime(
                listInstances = { error("sip:+155501@ims.example.com") },
                transportFor = { FakeCallTransport() },
                clock = { 0L },
            ),
            audio = FakeCallAudioController(),
            control = CallControlTransport { _, _, _, _ -> error("unexpected control request") },
            parentContext = coroutineContext,
        )

        val failure = assertIs<CallUiState.Failure>(source.state.value)
        assertEquals(CallFailureMessages.LIST, failure.message)
        assertCallPrivacy(failure)
        source.close()
    }

    @Test
    fun singleRunningEnginePublishesIdleAfterSnapshotWithoutControlCommands() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)

        val ready = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, ready.phase)
        assertEquals(CallAudioState.Idle, ready.audio)
        assertEquals(CallOrigin.ENGINE, ready.origin)
        assertFalse(ready.busy)
        assertEquals(null, ready.operationMessage)
        assertEquals("getCallSnapshot", transport.snapshotRequests.single().method)
        assertTrue(transport.requests.isEmpty())
        assertNoGetStatus(transport)
        assertCallPrivacy(ready)
        source.close()
    }

    @Test
    fun audioStartFailureKeepsInCallSoHangupRemainsPossible() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController(startHandler = { error("pcm eof sip:+155501") })
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.answer()
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")

        val failed = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.InCall, failed.phase)
        val audioFailed = assertIs<CallAudioState.Failed>(failed.audio)
        assertEquals(CallFailureMessages.AUDIO, audioFailed.message)
        assertCallPrivacy(failed, CALL_TOKEN, "pcm eof", "sip:+155501")

        source.hangup()
        assertEquals("hangup", transport.requests.last().method)
        assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(source.state.value).phase)
        source.close()
    }

    @Test
    fun audioEofPreservesConfirmedCallAndStopsController() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val audio = FakeCallAudioController()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport, audio)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        source.answer()
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        val stopsBefore = audio.stopCalls

        audio.emitEnded()
        val interrupted = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.InCall, interrupted.phase)
        assertIs<CallAudioState.Failed>(interrupted.audio)
        assertTrue(audio.stopCalls > stopsBefore)
        source.close()
    }

    @Test
    fun hangupEndedAndEofStopOnceWithoutReentrantDeadlock() = runTest(UnconfinedTestDispatcher()) {
        val hangupTransport = FakeCallTransport()
        val hangupAudio = FakeCallAudioController(emitEndedOnStop = true)
        val hangupSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), hangupTransport, hangupAudio)
        hangupAudio.stateReader = { hangupSource.state.value }
        hangupTransport.emitIncoming(CALL_TOKEN, "RINGING")
        hangupSource.answer()
        hangupTransport.emitIncoming(CALL_TOKEN, "IN_CALL")
        hangupSource.hangup()
        assertEquals(CallPhase.Idle, hangupAudio.phaseAtStop)
        assertEquals(1, hangupAudio.stopCalls)
        assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(hangupSource.state.value).phase)
        hangupSource.close()

        val endedTransport = FakeCallTransport()
        val endedAudio = FakeCallAudioController(emitEndedOnStop = true)
        val endedSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), endedTransport, endedAudio)
        endedAudio.stateReader = { endedSource.state.value }
        endedTransport.emitIncoming(CALL_TOKEN, "RINGING")
        endedSource.answer()
        endedTransport.emitIncoming(CALL_TOKEN, "IN_CALL")
        endedTransport.emitIncoming(CALL_TOKEN, "ENDED", "remote")
        assertEquals(CallPhase.Idle, endedAudio.phaseAtStop)
        assertEquals(1, endedAudio.stopCalls)
        endedSource.close()

        val eofTransport = FakeCallTransport()
        val eofAudio = FakeCallAudioController(emitEndedOnStop = true)
        val eofSource = sourceOf(listOf(callRunning(CALL_ICCID_A)), eofTransport, eofAudio)
        eofAudio.stateReader = { eofSource.state.value }
        eofTransport.emitIncoming(CALL_TOKEN, "RINGING")
        eofSource.answer()
        eofTransport.emitIncoming(CALL_TOKEN, "IN_CALL")
        eofAudio.emitEnded()
        assertEquals(CallPhase.InCall, eofAudio.phaseAtStop)
        assertIs<CallAudioState.Failed>(assertIs<CallUiState.Ready>(eofSource.state.value).audio)
        assertEquals(1, eofAudio.stopCalls)
        eofSource.close()
    }

    @Test
    fun callOriginIncludesDesktopFixtureForLaterUi() {
        assertEquals(
            setOf(CallOrigin.ENGINE, CallOrigin.DESKTOP_FIXTURE),
            CallOrigin.entries.toSet(),
        )
    }

    @Test
    fun closeCancelsFurtherUpdatesAndActions() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        transport.emitIncoming(CALL_TOKEN, "RINGING")
        val before = source.state.value

        source.close()
        transport.emitIncoming(CALL_TOKEN, "IN_CALL")
        source.answer()
        source.refresh()

        assertEquals(before, source.state.value)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun refreshReselectsSingleRunningInstance() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport()
        var instances = listOf(callStopped(CALL_ICCID_A), callRunning(CALL_ICCID_B))
        val source = EngineCallSource(
            runtime = EngineMessageRuntime(
                listInstances = { instances },
                transportFor = { transport },
                clock = { 0L },
            ),
            audio = FakeCallAudioController(),
            control = CallControlTransport { _, method, params, timeout -> transport.request(method, params, timeout) },
            parentContext = coroutineContext,
        )
        assertIs<CallUiState.Ready>(source.state.value)

        instances = listOf(callStopped(CALL_ICCID_A), callStopped(CALL_ICCID_B))
        source.refresh()
        assertIs<CallUiState.Unavailable>(source.state.value)

        instances = listOf(callRunning(CALL_ICCID_A), callRunning(CALL_ICCID_B))
        source.refresh()
        assertIs<CallUiState.Failure>(source.state.value)
        source.close()
    }
}
