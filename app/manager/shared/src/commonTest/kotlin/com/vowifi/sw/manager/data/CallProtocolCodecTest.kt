package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CallProtocolCodecTest {
    @Test
    fun tokenParamsEncodeOpaqueCallIdOnly() {
        val params = CallProtocolCodec.params(TOKEN)

        assertEquals(TOKEN, params.getValue("callId").jsonPrimitive.content)
        assertEquals(setOf("callId"), params.keys)
    }

    @Test
    fun audioStartParamsAreExact() {
        val params = CallProtocolCodec.audioStartParams(TOKEN)

        assertEquals(TOKEN, params.getValue("callId").jsonPrimitive.content)
        assertEquals(16_000, params.getValue("sampleRate").jsonPrimitive.int)
        assertEquals(1, params.getValue("channels").jsonPrimitive.int)
        assertEquals(setOf("callId", "sampleRate", "channels"), params.keys)
        assertEquals(5_000L, CallProtocolCodec.AUDIO_START_TIMEOUT_MILLIS)
        assertEquals(PcmFrameCodec.SAMPLE_RATE_HZ, params.getValue("sampleRate").jsonPrimitive.int)
        assertEquals(PcmFrameCodec.CHANNEL_COUNT, params.getValue("channels").jsonPrimitive.int)
    }

    @Test
    fun answerRequestUsesJsonParams() {
        val line = EngineProtocolCodec.request(11, "answer", CallProtocolCodec.params(TOKEN))
        val message = Json.parseToJsonElement(line.trimEnd()).jsonObject
        val params = message.getValue("params").jsonObject

        assertEquals("answer", message.getValue("method").jsonPrimitive.content)
        assertEquals(TOKEN, params.getValue("callId").jsonPrimitive.content)
        assertEquals(13_000L, CallProtocolCodec.ANSWER_TIMEOUT_MILLIS)
        assertEquals(5_000L, CallProtocolCodec.CONTROL_TIMEOUT_MILLIS)
        assertEquals(18_000L, CallProtocolCodec.DIAL_TIMEOUT_MILLIS)
    }

    @Test
    fun incomingCallDecodesRingingAndInCall() {
        val ringing = decodeEvent("""{"event":"incomingCall","data":{"callId":"$TOKEN","state":"RINGING"}}""")
        val ringingEvent = assertIs<CallWireEvent.Ringing>(ringing)
        assertEquals(TOKEN, ringingEvent.token)

        val inCall = decodeEvent("""{"event":"incomingCall","data":{"callId":"$TOKEN","state":"IN_CALL"}}""")
        val inCallEvent = assertIs<CallWireEvent.InCall>(inCall)
        assertEquals(TOKEN, inCallEvent.token)
    }

    @Test
    fun incomingCallDecodesEveryKnownEndedReason() {
        val expected = listOf(
            "rejected" to CallWireReason.REJECTED,
            "canceled" to CallWireReason.CANCELED,
            "timeout" to CallWireReason.TIMEOUT,
            "hangup" to CallWireReason.HANGUP,
            "remote" to CallWireReason.REMOTE,
            "failed" to CallWireReason.FAILED,
        )

        expected.forEach { (wire, reason) ->
            val event = decodeEvent(
                """{"event":"incomingCall","data":{"callId":"$TOKEN","state":"ENDED","reason":"$wire"}}""",
            )
            val ended = assertIs<CallWireEvent.Ended>(event)
            assertEquals(TOKEN, ended.token)
            assertEquals(reason, ended.reason)
            assertEquals(null, ended.durationSec)
        }
    }

    @Test
    fun callStateDecodesDialingAndInCall() {
        val dialing = decodeEvent("""{"event":"callState","data":{"callId":"$TOKEN","state":"DIALING"}}""")
        assertEquals(CallWireEvent.Dialing(TOKEN), dialing)

        val inCall = decodeEvent("""{"event":"callState","data":{"callId":"$TOKEN","state":"IN_CALL"}}""")
        assertEquals(CallWireEvent.InCall(TOKEN), inCall)
    }

    @Test
    fun callEndedDecodesHangupRemoteAndFailedWithOptionalDuration() {
        val hangup = decodeEvent("""{"event":"callEnded","data":{"callId":"$TOKEN","reason":"hangup"}}""")
        assertEquals(CallWireEvent.Ended(TOKEN, CallWireReason.HANGUP), hangup)

        val remote = decodeEvent(
            """{"event":"callEnded","data":{"callId":"$TOKEN","reason":"remote","durationSec":0}}""",
        )
        assertEquals(CallWireEvent.Ended(TOKEN, CallWireReason.REMOTE, durationSec = 0), remote)

        val failed = decodeEvent(
            """{"event":"callEnded","data":{"callId":"$TOKEN","reason":"failed","durationSec":12}}""",
        )
        assertEquals(CallWireEvent.Ended(TOKEN, CallWireReason.FAILED, durationSec = 12), failed)
    }

    @Test
    fun unknownEventNameStateAndReasonAreIgnored() {
        assertNull(decodeEvent("""{"event":"smsReceived","data":{"smsId":"mt-1"}}"""))
        assertNull(decodeEvent("""{"event":"incomingCall","data":{"callId":"$TOKEN","state":"DIALING"}}"""))
        assertNull(
            decodeEvent(
                """{"event":"incomingCall","data":{"callId":"$TOKEN","state":"ENDED","reason":"busy"}}""",
            ),
        )
        assertNull(decodeEvent("""{"event":"incomingCall","data":{"state":"RINGING"}}"""))
        assertNull(decodeEvent("""{"event":"incomingCall","data":{"callId":"","state":"RINGING"}}"""))
        assertNull(decodeEvent("""{"event":"incomingCall","data":{"callId":"$TOKEN","state":"ENDED"}}"""))
        assertNull(decodeEvent("""{"event":"callState","data":{"callId":"$TOKEN","state":"RINGING"}}"""))
        assertNull(decodeEvent("""{"event":"callState","data":{"callId":"","state":"DIALING"}}"""))
        assertNull(decodeEvent("""{"event":"callEnded","data":{"callId":"$TOKEN"}}"""))
        assertNull(decodeEvent("""{"event":"callEnded","data":{"callId":"$TOKEN","reason":"rejected"}}"""))
        assertNull(decodeEvent("""{"event":"callEnded","data":{"callId":"","reason":"hangup"}}"""))
    }

    @Test
    fun isValidDialNumberAcceptsOptionalPlusThenOneToTwentyDigits() {
        assertTrue(CallProtocolCodec.isValidDialNumber("1"))
        assertTrue(CallProtocolCodec.isValidDialNumber("+1"))
        assertTrue(CallProtocolCodec.isValidDialNumber(NUMBER))
        assertTrue(CallProtocolCodec.isValidDialNumber(NUMBER.removePrefix("+")))
        assertTrue(CallProtocolCodec.isValidDialNumber(DIGITS_20))
        assertTrue(CallProtocolCodec.isValidDialNumber("+$DIGITS_20"))
        listOf(
            "",
            "+",
            DIGITS_21,
            " 1",
            "1 ",
            "1 2",
            "a",
            "1a",
            "tel:+1",
            "sip:a@b",
            "++1",
            "１２３",
        ).forEach { number ->
            assertFalse(CallProtocolCodec.isValidDialNumber(number), number)
        }
    }

    @Test
    fun dialParamsEmitExactRecipientTelUriWithoutNormalizing() {
        val plus = CallProtocolCodec.dialParams(NUMBER)
        assertEquals(setOf("recipient"), plus.keys)
        assertEquals("tel:$NUMBER", plus.getValue("recipient").jsonPrimitive.content)

        val digits = CallProtocolCodec.dialParams("15550101")
        assertEquals(setOf("recipient"), digits.keys)
        assertEquals("tel:15550101", digits.getValue("recipient").jsonPrimitive.content)

        val raw = CallProtocolCodec.dialParams(" 1555")
        assertEquals("tel: 1555", raw.getValue("recipient").jsonPrimitive.content)
    }

    @Test
    fun dialResultTokenRequiresErrorFreeInCallAndNonEmptyCallId() {
        assertEquals(TOKEN, CallProtocolCodec.dialResultToken(response(TOKEN, "IN_CALL")))
        assertNull(CallProtocolCodec.dialResultToken(response("", "IN_CALL")))
        assertNull(CallProtocolCodec.dialResultToken(response(TOKEN, "DIALING")))
        assertNull(CallProtocolCodec.dialResultToken(response(TOKEN, "RINGING")))
        assertNull(
            CallProtocolCodec.dialResultToken(
                EngineProtocolFrame.Response(
                    id = 1,
                    result = buildJsonObject { put("state", "IN_CALL") },
                    error = null,
                ),
            ),
        )
        assertNull(
            CallProtocolCodec.dialResultToken(
                EngineProtocolFrame.Response(
                    id = 1,
                    result = buildJsonObject {
                        put("callId", TOKEN)
                        put("state", "IN_CALL")
                    },
                    error = EngineProtocolError(1001, "invalid call request"),
                ),
            ),
        )
        assertNull(
            CallProtocolCodec.dialResultToken(
                EngineProtocolFrame.Response(id = 1, result = null, error = null),
            ),
        )
    }

    @Test
    fun protocolErrorsMapToBoundedChineseMessages() {
        assertEquals(CallFailureMessages.INVALID, CallProtocolCodec.messageOf(EngineProtocolError(1001, "invalid call request")))
        assertEquals(CallFailureMessages.BUSY, CallProtocolCodec.messageOf(EngineProtocolError(2001, "busy")))
        assertEquals("无人接听", CallProtocolCodec.messageOf(EngineProtocolError(2002, "no_answer")))
        assertEquals(CallFailureMessages.NETWORK, CallProtocolCodec.messageOf(EngineProtocolError(2003, "network")))
        assertEquals(CallFailureMessages.IMS, CallProtocolCodec.messageOf(EngineProtocolError(3002, "IMS 未注册")))
        assertEquals(CallFailureMessages.AUDIO, CallProtocolCodec.messageOf(EngineProtocolError(5001, "audio_failed")))
        assertEquals(CallFailureMessages.NETWORK, CallProtocolCodec.messageOf(EngineProtocolError(9, "sip:bob@example.com timeout")))
        assertEquals(CallFailureMessages.NETWORK, CallProtocolCodec.messageOf(null))
    }

    private fun decodeEvent(line: String): CallWireEvent? {
        val frame = EngineProtocolCodec.decode(line)
        return CallProtocolCodec.decodeEvent(assertIs<EngineProtocolFrame.Event>(frame))
    }

    private fun response(callId: String, state: String) = EngineProtocolFrame.Response(
        id = 1,
        result = buildJsonObject {
            put("callId", callId)
            put("state", state)
        },
        error = null,
    )
}

private const val TOKEN = "tok-synth-1"
private const val NUMBER = "+15550101"
private const val DIGITS_20 = "12345678901234567890"
private const val DIGITS_21 = "123456789012345678901"
