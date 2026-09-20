package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallPeer
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallSessionDialReducerTest {
    @Test
    fun beginDialOnlyFromIdleNotBusyMovesToDialingWithoutToken() {
        assertEquals(
            CallSession(phase = CallPhase.Dialing, busy = true),
            CallSessionReducer.beginDial(CallSession()),
        )
        assertNull(CallSessionReducer.beginDial(CallSession(busy = true)))
        assertNull(CallSessionReducer.beginDial(CallSession(phase = CallPhase.Dialing, busy = true)))
        assertNull(CallSessionReducer.beginDial(CallSession(token = TOKEN, phase = CallPhase.Ringing)))
        assertNull(CallSessionReducer.beginDial(CallSession(token = TOKEN, phase = CallPhase.Connecting)))
        assertNull(CallSessionReducer.beginDial(CallSession(token = TOKEN, phase = CallPhase.InCall)))
        val started = CallSessionReducer.beginDial(CallSession())!!
        assertEquals(null, started.token)
        assertTrue(CallPhase.Dialing != CallPhase.Connecting)
    }

    @Test
    fun completeDialFromDialingAdoptsTokenOrReturnsIdle() {
        val dialing = CallSession(phase = CallPhase.Dialing, busy = true)

        assertEquals(
            CallSession(token = TOKEN, phase = CallPhase.InCall),
            CallSessionReducer.completeDial(dialing, TOKEN, operationMessage = null),
        )
        assertEquals(
            CallSession(operationMessage = CallFailureMessages.NETWORK),
            CallSessionReducer.completeDial(dialing, TOKEN, CallFailureMessages.NETWORK),
        )
        assertEquals(
            CallSession(operationMessage = CallFailureMessages.INVALID),
            CallSessionReducer.completeDial(dialing, resultToken = "", operationMessage = null),
        )
        assertEquals(
            CallSession(operationMessage = CallFailureMessages.INVALID),
            CallSessionReducer.completeDial(dialing, resultToken = null, operationMessage = null),
        )
    }

    @Test
    fun completeDialOnIdleOrInCallDoesNotResurrectOrRestartAudio() {
        val idleBusy = CallSession(busy = true)
        assertEquals(
            CallSession(),
            CallSessionReducer.completeDial(idleBusy, TOKEN, operationMessage = null),
        )
        assertEquals(
            CallSession(),
            CallSessionReducer.completeDial(CallSession(), TOKEN, CallFailureMessages.NETWORK),
        )

        val inCall = CallSession(token = TOKEN, phase = CallPhase.InCall, busy = true, audio = CallAudioState.Active)
        assertEquals(
            inCall.copy(busy = false),
            CallSessionReducer.completeDial(inCall, TOKEN, operationMessage = null),
        )
        assertEquals(
            inCall.copy(busy = false),
            CallSessionReducer.completeDial(inCall, OTHER, CallFailureMessages.NETWORK),
        )
    }

    @Test
    fun dialingEventAdoptsFirstTokenThenIgnoresMismatchAndForeignPhases() {
        val waiting = CallSession(phase = CallPhase.Dialing, busy = true)
        val adopted = CallSessionReducer.applyEvent(waiting, dialing(TOKEN))
        assertEquals(CallSession(token = TOKEN, phase = CallPhase.Dialing, busy = true), adopted)
        assertEquals(adopted, CallSessionReducer.applyEvent(adopted, dialing(TOKEN)))
        assertEquals(adopted, CallSessionReducer.applyEvent(adopted, dialing(OTHER)))

        val ringing = CallSession(token = TOKEN, phase = CallPhase.Ringing)
        val connecting = CallSession(token = TOKEN, phase = CallPhase.Connecting)
        val inCall = CallSession(token = TOKEN, phase = CallPhase.InCall)
        assertEquals(CallSession(), CallSessionReducer.applyEvent(CallSession(), dialing(TOKEN)))
        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, dialing(TOKEN)))
        assertEquals(connecting, CallSessionReducer.applyEvent(connecting, dialing(TOKEN)))
        assertEquals(inCall, CallSessionReducer.applyEvent(inCall, dialing(TOKEN)))
    }

    @Test
    fun inCallAndEndedClaimFirstDialingTokenThenRequireOwnedToken() {
        val waiting = CallSession(phase = CallPhase.Dialing, busy = true)
        val claimed = CallSessionReducer.applyEvent(waiting, inCall(TOKEN))
        assertEquals(CallSession(token = TOKEN, phase = CallPhase.InCall), claimed)
        assertEquals(CallSession(), CallSessionReducer.applyEvent(waiting, ended(TOKEN)))

        val owned = CallSession(token = TOKEN, phase = CallPhase.Dialing)
        assertEquals(
            CallSession(token = TOKEN, phase = CallPhase.InCall),
            CallSessionReducer.applyEvent(owned, inCall(TOKEN)),
        )
        assertEquals(owned, CallSessionReducer.applyEvent(owned, inCall(OTHER)))
        assertEquals(owned, CallSessionReducer.applyEvent(owned, ended(OTHER)))
        assertEquals(CallSession(), CallSessionReducer.applyEvent(owned, ended(TOKEN)))
    }

    @Test
    fun ringingDuringDialingIsIgnored() {
        val waiting = CallSession(phase = CallPhase.Dialing, busy = true)
        val owned = CallSession(token = TOKEN, phase = CallPhase.Dialing)
        assertEquals(waiting, CallSessionReducer.applyEvent(waiting, ringing(TOKEN)))
        assertEquals(owned, CallSessionReducer.applyEvent(owned, ringing(TOKEN)))
    }

    @Test
    fun beginHangupFromDialingReturnsNullEvenWithToken() {
        assertNull(CallSessionReducer.beginHangup(CallSession(token = TOKEN, phase = CallPhase.Dialing)))
        assertNull(
            CallSessionReducer.beginHangup(CallSession(token = TOKEN, phase = CallPhase.Dialing, busy = false)),
        )
        assertEquals(
            CallSession(token = TOKEN, phase = CallPhase.InCall, busy = true),
            CallSessionReducer.beginHangup(CallSession(token = TOKEN, phase = CallPhase.InCall)),
        )
    }

    @Test
    fun sessionNeverStoresDialedNumber() {
        val dialInput = "+15550101"
        val started = CallSessionReducer.beginDial(CallSession())!!
        val adopted = CallSessionReducer.applyEvent(started, dialing(TOKEN))
        val connected = CallSessionReducer.completeDial(started, TOKEN, operationMessage = null)
        val ui = CallUiState.Ready(
            phase = started.phase,
            audio = started.audio,
            origin = CallOrigin.ENGINE,
            busy = started.busy,
            operationMessage = started.operationMessage,
            peer = started.peer,
        )
        val enginePeer = requireNotNull(CallPeer.parse("15551212"))
        val incoming = CallSessionReducer.applyEvent(CallSession(), CallWireEvent.Ringing(TOKEN, enginePeer))
        assertEquals(null, started.token)
        assertEquals(TOKEN, adopted.token)
        assertEquals(TOKEN, connected.token)
        assertEquals(CallPhase.Dialing, started.phase)
        assertEquals(CallPhase.InCall, connected.phase)
        assertEquals(CallAudioState.Idle, connected.audio)
        assertEquals(null, started.peer)
        assertEquals(null, adopted.peer)
        assertEquals(null, connected.peer)
        assertEquals(null, ui.peer)
        assertEquals(enginePeer, incoming.peer)
        listOf(started, adopted, connected, ui, incoming).forEach { value ->
            assertFalse(value.toString().contains(dialInput))
            assertFalse(value.toString().contains(dialInput.removePrefix("+")))
            assertFalse(value.toString().contains("15551212"))
        }
    }
}

private fun ringing(token: String) = CallWireEvent.Ringing(token)
private fun dialing(token: String) = CallWireEvent.Dialing(token)
private fun inCall(token: String) = CallWireEvent.InCall(token)
private fun ended(token: String) = CallWireEvent.Ended(token, CallWireReason.REMOTE)

private const val TOKEN = "tok-synth-1"
private const val OTHER = "tok-other-2"
