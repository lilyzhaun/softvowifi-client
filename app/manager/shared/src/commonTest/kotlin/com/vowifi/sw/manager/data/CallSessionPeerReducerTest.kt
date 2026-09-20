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

class CallSessionPeerReducerTest {
    @Test
    fun incomingPeerIsAdoptedAndPreservedThroughRingingConnectingInCall() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLUS))
        val other = requireNotNull(CallPeer.parse(SYNTH_PLAIN))
        val ringing = CallSessionReducer.applyEvent(CallSession(), ringing(TOKEN, peer))
        val answering = CallSessionReducer.beginAnswer(ringing)!!
        val connecting = CallSessionReducer.completeAnswer(answering, operationMessage = null)
        val inCall = CallSessionReducer.applyEvent(connecting, inCall(TOKEN, other))
        val ui = readyUi(ringing)

        assertEquals(CallPhase.Ringing, ringing.phase)
        assertEquals(peer, ringing.peer)
        assertEquals(peer, answering.peer)
        assertEquals(CallPhase.Connecting, connecting.phase)
        assertEquals(peer, connecting.peer)
        assertEquals(CallPhase.InCall, inCall.phase)
        assertEquals(peer, inCall.peer)
        assertEquals(peer, ui.peer)
        assertEquals(null, CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE).peer)
    }

    @Test
    fun laterMatchingEventAdoptsPeerWhenSessionPeerIsAbsent() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLAIN))
        val ringing = CallSessionReducer.applyEvent(CallSession(), ringing(TOKEN))
        val duplicate = CallSessionReducer.applyEvent(ringing, ringing(TOKEN, peer))
        val connected = CallSessionReducer.applyEvent(duplicate, inCall(TOKEN, peer))

        assertEquals(null, ringing.peer)
        assertEquals(peer, duplicate.peer)
        assertEquals(peer, connected.peer)
        assertEquals(CallPhase.Ringing, duplicate.phase)
    }

    @Test
    fun staleOrDifferentTokenCannotReplacePeer() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLUS))
        val other = requireNotNull(CallPeer.parse(SYNTH_PLAIN))
        val ringing = CallSessionReducer.applyEvent(CallSession(), ringing(TOKEN, peer))

        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, ringing(OTHER, other)))
        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, inCall(OTHER, other)))
        assertEquals(ringing, CallSessionReducer.applyEvent(ringing, ended(OTHER, other, 8)))
        assertEquals(peer, ringing.peer)
        val change = CallSessionReducer.reduce(ringing, ended(OTHER, other, 8))
        assertEquals(ringing, change.session)
        assertNull(change.ended)
    }

    @Test
    fun endedCarriesMetadataForReducerSideEffectThenVisibleIdle() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLUS))
        val inCall = CallSession(
            token = TOKEN,
            phase = CallPhase.InCall,
            audio = CallAudioState.Active,
            peer = peer,
        )
        val change = CallSessionReducer.reduce(inCall, ended(TOKEN, peer, 7))
        val ui = readyUi(change.session)

        assertEquals(CallSession(), change.session)
        assertEquals(CallPhase.Idle, change.session.phase)
        assertEquals(null, change.session.peer)
        assertEquals(TOKEN, change.ended?.token)
        assertEquals(CallWireReason.REMOTE, change.ended?.reason)
        assertEquals(peer, change.ended?.peer)
        assertEquals(7, change.ended?.durationSec)
        assertEquals(CallPhase.Idle, ui.phase)
        assertEquals(null, ui.peer)
        assertFalse(change.ended.toString().contains(SYNTH_PLUS))
        assertFalse(change.ended.toString().contains(TOKEN))
    }

    @Test
    fun outgoingDialPathNeverStoresLocalDialInputOrWirePeer() {
        val dialInput = SYNTH_PLUS
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLAIN))
        val started = CallSessionReducer.beginDial(CallSession())!!
        val adopted = CallSessionReducer.applyEvent(started, CallWireEvent.Dialing(TOKEN))
        val claimed = CallSessionReducer.applyEvent(started, inCall(TOKEN, peer))
        val connected = CallSessionReducer.completeDial(started, TOKEN, operationMessage = null)
        val ui = readyUi(started)

        assertEquals(null, started.peer)
        assertEquals(null, adopted.peer)
        assertEquals(null, claimed.peer)
        assertEquals(null, connected.peer)
        assertEquals(null, ui.peer)
        assertEquals(CallPhase.Dialing, started.phase)
        assertEquals(CallPhase.InCall, claimed.phase)
        listOf(started, adopted, claimed, connected, ui).forEach { value ->
            assertFalse(value.toString().contains(dialInput))
            assertFalse(value.toString().contains(dialInput.removePrefix("+")))
            assertFalse(value.toString().contains(SYNTH_PLAIN))
        }
    }

    @Test
    fun publicToStringNeverExposesPeerDigitsOrToken() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLUS))
        val session = CallSessionReducer.applyEvent(CallSession(), ringing(TOKEN, peer))
        val ready = readyUi(session)
        val ended = CallEndedEffect(TOKEN, CallWireReason.HANGUP, peer, 4)

        listOf(peer.toString(), session.toString(), ready.toString(), ended.toString()).forEach { text ->
            assertFalse(text.contains(SYNTH_PLUS), text)
            assertFalse(text.contains(SYNTH_PLUS.removePrefix("+")), text)
            assertFalse(text.contains(TOKEN), text)
        }
        assertEquals(peer, ready.peer)
        assertEquals(SYNTH_PLUS, ready.peer?.text())
    }

    @Test
    fun completeAnswerPreservesPeerAndFailureDoesNotClearIt() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLAIN))
        val ringing = CallSession(token = TOKEN, phase = CallPhase.Ringing, peer = peer, busy = true)
        val failed = CallSessionReducer.completeAnswer(ringing, CallFailureMessages.INVALID)
        val connected = CallSessionReducer.completeAnswer(ringing, operationMessage = null)

        assertEquals(peer, failed.peer)
        assertEquals(peer, connected.peer)
        assertEquals(CallPhase.Ringing, failed.phase)
        assertEquals(CallPhase.Connecting, connected.phase)
    }
}

private fun ringing(token: String, peer: CallPeer? = null) = CallWireEvent.Ringing(token, peer)

private fun inCall(token: String, peer: CallPeer? = null) = CallWireEvent.InCall(token, peer)

private fun ended(token: String, peer: CallPeer? = null, durationSec: Int? = null) =
    CallWireEvent.Ended(token, CallWireReason.REMOTE, durationSec, peer)

private fun readyUi(session: CallSession) = CallUiState.Ready(
    phase = session.phase,
    audio = session.audio,
    origin = CallOrigin.ENGINE,
    busy = session.busy,
    operationMessage = session.operationMessage,
    peer = session.peer,
)

private const val TOKEN = "tok-synth-1"
private const val OTHER = "tok-other-2"
private const val SYNTH_PLAIN = "15551212"
private const val SYNTH_PLUS = "+15550001"
