package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPeer
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CallProtocolPeerCodecTest {
    @Test
    fun oldIncomingCallStateAndEndedWithoutPeerOrDurationStayUnchanged() {
        val ringing = decode("""{"event":"incomingCall","data":{"callId":"$TOKEN","state":"RINGING"}}""")
        val ringingEvent = assertIs<CallWireEvent.Ringing>(ringing)
        assertEquals(TOKEN, ringingEvent.token)
        assertEquals(null, ringingEvent.peer)

        val inCall = decode("""{"event":"incomingCall","data":{"callId":"$TOKEN","state":"IN_CALL"}}""")
        val inCallEvent = assertIs<CallWireEvent.InCall>(inCall)
        assertEquals(TOKEN, inCallEvent.token)
        assertEquals(null, inCallEvent.peer)

        val incomingEnded = decode(
            """{"event":"incomingCall","data":{"callId":"$TOKEN","state":"ENDED","reason":"hangup"}}""",
        )
        val incomingEndedEvent = assertIs<CallWireEvent.Ended>(incomingEnded)
        assertEquals(TOKEN, incomingEndedEvent.token)
        assertEquals(CallWireReason.HANGUP, incomingEndedEvent.reason)
        assertEquals(null, incomingEndedEvent.peer)
        assertEquals(null, incomingEndedEvent.durationSec)

        val dialing = decode("""{"event":"callState","data":{"callId":"$TOKEN","state":"DIALING"}}""")
        assertEquals(CallWireEvent.Dialing(TOKEN), dialing)

        val outgoingEnded = decode("""{"event":"callEnded","data":{"callId":"$TOKEN","reason":"remote"}}""")
        val outgoingEndedEvent = assertIs<CallWireEvent.Ended>(outgoingEnded)
        assertEquals(null, outgoingEndedEvent.peer)
        assertEquals(null, outgoingEndedEvent.durationSec)
    }

    @Test
    fun validPeerDecodesOnRingingInCallAndEnded() {
        val peer = requireNotNull(CallPeer.parse(SYNTH_PLUS))
        val ringing = decode(incoming("RINGING", peer = SYNTH_PLUS))
        val inCall = decode(incoming("IN_CALL", peer = SYNTH_PLAIN))
        val ended = decode(incoming("ENDED", reason = "remote", peer = SYNTH_PLUS, durationSec = 9))

        assertEquals(peer, assertIs<CallWireEvent.Ringing>(ringing).peer)
        assertEquals(CallPeer.parse(SYNTH_PLAIN), assertIs<CallWireEvent.InCall>(inCall).peer)
        val endedEvent = assertIs<CallWireEvent.Ended>(ended)
        assertEquals(peer, endedEvent.peer)
        assertEquals(9, endedEvent.durationSec)
        assertFalse(ringing.toString().contains(SYNTH_PLUS.removePrefix("+")))
    }

    @Test
    fun optionalDurationOnEndedAcceptsBoundedNonnegativeNumericOnly() {
        val zero = decode(incoming("ENDED", reason = "hangup", durationSec = 0))
        val max = decode(incoming("ENDED", reason = "hangup", durationSec = CallProtocolCodec.MAX_DURATION_SEC))
        val over = decode(incoming("ENDED", reason = "hangup", durationSec = CallProtocolCodec.MAX_DURATION_SEC + 1))
        val negative = decodeEvent(
            event("incomingCall", buildJsonObject {
                put("callId", TOKEN)
                put("state", "ENDED")
                put("reason", "hangup")
                put("durationSec", -1)
            }),
        )
        val asString = decodeEvent(
            event("incomingCall", buildJsonObject {
                put("callId", TOKEN)
                put("state", "ENDED")
                put("reason", "hangup")
                put("durationSec", "12")
            }),
        )
        val outgoing = decode("""{"event":"callEnded","data":{"callId":"$TOKEN","reason":"failed","durationSec":4}}""")

        assertEquals(0, assertIs<CallWireEvent.Ended>(zero).durationSec)
        assertEquals(CallProtocolCodec.MAX_DURATION_SEC, assertIs<CallWireEvent.Ended>(max).durationSec)
        assertEquals(null, assertIs<CallWireEvent.Ended>(over).durationSec)
        assertEquals(null, assertIs<CallWireEvent.Ended>(negative).durationSec)
        assertEquals(null, assertIs<CallWireEvent.Ended>(asString).durationSec)
        assertEquals(4, assertIs<CallWireEvent.Ended>(outgoing).durationSec)
        assertEquals(CallWireReason.FAILED, assertIs<CallWireEvent.Ended>(outgoing).reason)
    }

    @Test
    fun nonStringPeerDoesNotTextualizeOrDropCoreCallState() {
        listOf<Pair<String, kotlinx.serialization.json.JsonElement>>(
            "numeric" to JsonPrimitive(15551212),
            "boolean" to JsonPrimitive(true),
            "null" to JsonNull,
            "object" to buildJsonObject { put("n", SYNTH_PLAIN) },
            "array" to buildJsonArray { add(SYNTH_PLAIN) },
        ).forEach { (label, value) ->
            val decoded = decodeEvent(
                event("incomingCall", buildJsonObject {
                    put("callId", TOKEN)
                    put("state", "RINGING")
                    put("peer", value)
                }),
            )
            val ringing = assertIs<CallWireEvent.Ringing>(decoded, label)
            assertEquals(TOKEN, ringing.token, label)
            assertEquals(null, ringing.peer, label)
            assertFalse(ringing.toString().contains(SYNTH_PLAIN), label)
        }
    }

    @Test
    fun rawSipMalformedPeerBecomesAbsentWhileCallStateRemains() {
        listOf(
            "sip:alice@example.invalid",
            "sips:alice@example.invalid",
            "tel:+15550001",
            "raw:15551212",
            "alice@example.invalid",
            "peer1555",
            "\u000115551212",
            "123456789012345678901",
        ).forEach { raw ->
            val ringing = assertIs<CallWireEvent.Ringing>(decode(incoming("RINGING", peer = raw)))
            assertEquals(TOKEN, ringing.token, raw)
            assertEquals(null, ringing.peer, raw)
            assertFalse(ringing.toString().contains(raw), raw)
            assertFalse(ringing.toString().contains("sip:"), raw)
        }
    }

    @Test
    fun callStateIgnoresPeerEvenWhenPresent() {
        val dialing = decodeEvent(
            event("callState", buildJsonObject {
                put("callId", TOKEN)
                put("state", "DIALING")
                put("peer", SYNTH_PLUS)
            }),
        )
        val inCall = decodeEvent(
            event("callState", buildJsonObject {
                put("callId", TOKEN)
                put("state", "IN_CALL")
                put("peer", SYNTH_PLUS)
            }),
        )
        val ended = decodeEvent(
            event("callEnded", buildJsonObject {
                put("callId", TOKEN)
                put("reason", "hangup")
                put("peer", SYNTH_PLUS)
                put("durationSec", 3)
            }),
        )

        assertEquals(CallWireEvent.Dialing(TOKEN), dialing)
        assertEquals(null, assertIs<CallWireEvent.InCall>(inCall).peer)
        assertEquals(null, assertIs<CallWireEvent.Ended>(ended).peer)
        assertEquals(3, assertIs<CallWireEvent.Ended>(ended).durationSec)
    }
}

private fun decode(line: String): CallWireEvent? {
    val frame = EngineProtocolCodec.decode(line)
    return CallProtocolCodec.decodeEvent(assertIs<EngineProtocolFrame.Event>(frame))
}

private fun decodeEvent(event: EngineProtocolFrame.Event): CallWireEvent? =
    CallProtocolCodec.decodeEvent(event)

private fun event(name: String, data: JsonObject) = EngineProtocolFrame.Event(name = name, data = data)

private fun incoming(
    state: String,
    reason: String? = null,
    peer: String? = null,
    durationSec: Int? = null,
): String {
    val extras = buildString {
        if (reason != null) append(""","reason":"$reason"""")
        if (peer != null) append(""","peer":"$peer"""")
        if (durationSec != null) append(""","durationSec":$durationSec""")
    }
    return """{"event":"incomingCall","data":{"callId":"$TOKEN","state":"$state"$extras}}"""
}

private const val TOKEN = "tok-synth-1"
private const val SYNTH_PLAIN = "15551212"
private const val SYNTH_PLUS = "+15550001"
