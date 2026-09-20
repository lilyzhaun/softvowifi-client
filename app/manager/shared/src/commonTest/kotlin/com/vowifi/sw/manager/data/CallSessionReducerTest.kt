package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallSessionReducerTest {
    @Test
    fun ringingTokenMovesIdleToRinging() {
        val next = CallSessionReducer.applyEvent(CallSession(), ringing(TOKEN))

        assertEquals(TOKEN, next.token)
        assertEquals(CallPhase.Ringing, next.phase)
        assertEquals(CallAudioState.Idle, next.audio)
        assertEquals(false, next.busy)
    }

    @Test
    fun duplicateRingingSameTokenStaysRinging() {
        val ringing = CallSession(token = TOKEN, phase = CallPhase.Ringing)

        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, ringing(TOKEN)))
    }

    @Test
    fun tokenMismatchAndUnknownPhaseStayUnchanged() {
        val ringing = CallSession(token = TOKEN, phase = CallPhase.Ringing)
        val connecting = CallSession(token = TOKEN, phase = CallPhase.Connecting)

        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, ringing(OTHER)))
        assertEquals(connecting, CallSessionReducer.applyEvent(connecting, ringing(TOKEN)))
        assertEquals(CallSession(), CallSessionReducer.applyEvent(CallSession(), inCall(TOKEN)))
        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, inCall(OTHER)))
        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, ended(OTHER)))
    }

    @Test
    fun matchingInCallMovesAnyOwnedPhaseToInCallWithoutStartingAudio() {
        val connecting = CallSession(token = TOKEN, phase = CallPhase.Connecting, busy = false)
        val next = CallSessionReducer.applyEvent(connecting, inCall(TOKEN))

        assertEquals(CallPhase.InCall, next.phase)
        assertEquals(TOKEN, next.token)
        assertEquals(CallAudioState.Idle, next.audio)
    }

    @Test
    fun matchingEndedFromAnyPhaseReturnsIdleIdempotently() {
        val phases = listOf(
            CallSession(token = TOKEN, phase = CallPhase.Ringing),
            CallSession(token = TOKEN, phase = CallPhase.Connecting),
            CallSession(token = TOKEN, phase = CallPhase.Dialing),
            CallSession(token = TOKEN, phase = CallPhase.InCall, audio = CallAudioState.Active),
            CallSession(
                token = TOKEN,
                phase = CallPhase.InCall,
                audio = CallAudioState.Failed(CallFailureMessages.AUDIO),
            ),
        )

        phases.forEach { session ->
            assertEquals(CallSession(), CallSessionReducer.applyEvent(session, ended(TOKEN)))
        }
        assertEquals(CallSession(), CallSessionReducer.applyEvent(CallSession(), ended(TOKEN)))
    }

    @Test
    fun answerIsOnlyValidFromIdleBusyRingingAndMovesToConnectingOnSuccess() {
        assertNull(CallSessionReducer.beginAnswer(CallSession()))
        assertNull(CallSessionReducer.beginAnswer(CallSession(token = TOKEN, phase = CallPhase.Connecting)))
        assertNull(CallSessionReducer.beginAnswer(CallSession(token = TOKEN, phase = CallPhase.InCall)))
        assertNull(CallSessionReducer.beginAnswer(CallSession(phase = CallPhase.Dialing)))
        assertNull(
            CallSessionReducer.beginAnswer(CallSession(token = TOKEN, phase = CallPhase.Ringing, busy = true)),
        )

        val started = CallSessionReducer.beginAnswer(CallSession(token = TOKEN, phase = CallPhase.Ringing))
        assertEquals(CallSession(token = TOKEN, phase = CallPhase.Ringing, busy = true), started)

        val connected = CallSessionReducer.completeAnswer(started!!, operationMessage = null)
        assertEquals(CallSession(token = TOKEN, phase = CallPhase.Connecting, busy = false), connected)

        val failed = CallSessionReducer.completeAnswer(started, CallFailureMessages.INVALID)
        assertEquals(
            CallSession(
                token = TOKEN,
                phase = CallPhase.Ringing,
                busy = false,
                operationMessage = CallFailureMessages.INVALID,
            ),
            failed,
        )
    }

    @Test
    fun rejectOnlyRingingAndHangupFromInCallOrOwnedDialing() {
        val ringing = CallSession(token = TOKEN, phase = CallPhase.Ringing)
        val inCall = CallSession(token = TOKEN, phase = CallPhase.InCall, audio = CallAudioState.Active)
        val dialing = CallSession(token = TOKEN, phase = CallPhase.Dialing)

        assertNull(CallSessionReducer.beginReject(CallSession()))
        assertNull(CallSessionReducer.beginReject(inCall))
        assertEquals(
            ringing.copy(busy = true),
            CallSessionReducer.beginReject(ringing),
        )
        assertEquals(
            CallSession(),
            CallSessionReducer.completeReject(ringing.copy(busy = true), operationMessage = null),
        )
        assertEquals(
            ringing.copy(operationMessage = CallFailureMessages.BUSY),
            CallSessionReducer.completeReject(ringing.copy(busy = true), CallFailureMessages.BUSY),
        )

        assertNull(CallSessionReducer.beginHangup(ringing))
        assertNull(CallSessionReducer.beginHangup(CallSession(token = TOKEN, phase = CallPhase.Connecting)))
        assertNull(CallSessionReducer.beginHangup(CallSession()))
        assertNull(CallSessionReducer.beginHangup(CallSession(phase = CallPhase.Dialing)))
        assertNull(CallSessionReducer.beginHangup(dialing))
        assertEquals(inCall.copy(busy = true), CallSessionReducer.beginHangup(inCall))
        assertEquals(
            CallSession(),
            CallSessionReducer.completeHangup(inCall.copy(busy = true), operationMessage = null),
        )
        assertEquals(
            CallSession(),
            CallSessionReducer.completeHangup(dialing.copy(busy = true), operationMessage = null),
        )
        assertEquals(
            inCall.copy(operationMessage = CallFailureMessages.NETWORK),
            CallSessionReducer.completeHangup(inCall.copy(busy = true), CallFailureMessages.NETWORK),
        )
        assertEquals(
            dialing.copy(operationMessage = CallFailureMessages.NETWORK),
            CallSessionReducer.completeHangup(dialing.copy(busy = true), CallFailureMessages.NETWORK),
        )
    }

    @Test
    fun audioStartAndFailureOnlyApplyWhileInCall() {
        val inCall = CallSession(token = TOKEN, phase = CallPhase.InCall)
        val started = CallSessionReducer.audioStarted(inCall)
        assertEquals(CallAudioState.Active, started.audio)
        assertEquals(started, CallSessionReducer.audioStarted(started))

        val failed = CallSessionReducer.audioFailed(inCall)
        assertEquals(CallPhase.InCall, failed.phase)
        assertEquals(CallAudioState.Failed(CallFailureMessages.AUDIO), failed.audio)

        val ringing = CallSession(token = TOKEN, phase = CallPhase.Ringing)
        val dialing = CallSession(token = TOKEN, phase = CallPhase.Dialing)
        assertEquals(ringing, CallSessionReducer.audioStarted(ringing))
        assertEquals(ringing, CallSessionReducer.audioFailed(ringing))
        assertEquals(dialing, CallSessionReducer.audioStarted(dialing))
        assertEquals(dialing, CallSessionReducer.audioFailed(dialing))
        assertEquals(CallSession(), CallSessionReducer.audioFailed(CallSession()))
    }

    @Test
    fun completeAnswerAfterEndedStaysIdle() {
        val idle = CallSessionReducer.completeAnswer(CallSession(), operationMessage = null)
        assertEquals(CallSession(), idle)
    }

    @Test
    fun appliedEventsAndSuccessfulActionsClearOperationMessage() {
        val failed = CallSession(
            token = TOKEN,
            phase = CallPhase.Ringing,
            operationMessage = CallFailureMessages.INVALID,
        )
        assertEquals(null, CallSessionReducer.applyEvent(CallSession(), ringing(TOKEN)).operationMessage)
        assertEquals(
            null,
            CallSessionReducer.applyEvent(
                CallSession(token = TOKEN, phase = CallPhase.Connecting, operationMessage = CallFailureMessages.NETWORK),
                inCall(TOKEN),
            ).operationMessage,
        )
        assertEquals(null, CallSessionReducer.applyEvent(failed, ended(TOKEN)).operationMessage)
        assertEquals(
            failed,
            CallSessionReducer.applyEvent(failed, ringing(TOKEN)),
        )
        assertEquals(
            null,
            CallSessionReducer.completeAnswer(failed.copy(busy = true), operationMessage = null).operationMessage,
        )
    }
}

private fun ringing(token: String) = CallWireEvent.Ringing(token)
private fun inCall(token: String) = CallWireEvent.InCall(token)
private fun ended(token: String) = CallWireEvent.Ended(token, CallWireReason.REMOTE)

private const val TOKEN = "tok-synth-1"
private const val OTHER = "tok-other-2"
