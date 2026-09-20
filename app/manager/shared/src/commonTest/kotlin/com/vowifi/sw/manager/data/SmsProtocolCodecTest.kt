package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SmsProtocolCodecTest {
    @Test
    fun sendSmsParamsEncodeNumberAndText() {
        val params = SmsProtocolCodec.params(SYNTH_NUMBER, SYNTH_TEXT)

        assertEquals(SYNTH_NUMBER, params.getValue("number").jsonPrimitive.content)
        assertEquals(SYNTH_TEXT, params.getValue("text").jsonPrimitive.content)
        assertEquals(setOf("number", "text"), params.keys)
    }

    @Test
    fun anyTwoXxStatusCodeIsAccepted() {
        assertTrue(SmsProtocolCodec.isAcceptedStatusCode(200))
        assertTrue(SmsProtocolCodec.isAcceptedStatusCode(202))
        assertTrue(SmsProtocolCodec.isAcceptedStatusCode(299))
        assertFalse(SmsProtocolCodec.isAcceptedStatusCode(199))
        assertFalse(SmsProtocolCodec.isAcceptedStatusCode(300))
    }

    @Test
    fun sendResultDecodesSmsIdStatusCodeAndReference() {
        val frame = EngineProtocolCodec.decode(
            """{"id":20,"result":{"smsId":"$MO_ID","statusCode":202,"reference":66}}""",
        )

        val result = SmsProtocolCodec.sendResult(assertIs(frame))
        assertEquals(MO_ID, result.smsId)
        assertEquals(202, result.statusCode)
        assertEquals(66, result.reference)
    }

    @Test
    fun smsReceivedDecodesIncomingMtFields() {
        val event = decodeEvent(
            """{"event":"smsReceived","data":{"smsId":"$MT_ID","number":"$SYNTH_NUMBER","text":"$SYNTH_TEXT","timestamp":1755000000,"smsc":"+155500","reference":7}}""",
        )

        val received = assertIs<SmsWireEvent.Received>(event)
        assertEquals(MT_ID, received.smsId)
        assertEquals(SYNTH_NUMBER, received.number)
        assertEquals(SYNTH_TEXT, received.text)
        assertEquals(1_755_000_000L, received.timestampSeconds)
        assertEquals(7, received.reference)
    }

    @Test
    fun smsSentDecodesEveryKnownStatus() {
        val expected = listOf(
            "accepted" to SmsEngineStatus.ACCEPTED,
            "pending" to SmsEngineStatus.PENDING,
            "delivered" to SmsEngineStatus.DELIVERED,
            "forwarded" to SmsEngineStatus.FORWARDED,
            "replaced" to SmsEngineStatus.REPLACED,
            "failed" to SmsEngineStatus.FAILED,
        )

        expected.forEach { (wire, status) ->
            val event = decodeEvent(
                """{"event":"smsSent","data":{"smsId":"$MO_ID","status":"$wire","reference":66}}""",
            )
            val sent = assertIs<SmsWireEvent.Sent>(event)
            assertEquals(MO_ID, sent.smsId)
            assertEquals(status, sent.status)
            assertEquals(66, sent.reference)
        }
    }

    @Test
    fun unknownEventNameAndUnknownStatusAreIgnored() {
        assertNull(decodeEvent("""{"event":"tunnelState","data":{"state":"CONNECTED"}}"""))
        assertNull(
            decodeEvent(
                """{"event":"smsSent","data":{"smsId":"$MO_ID","status":"queued","reference":66}}""",
            ),
        )
    }

    @Test
    fun sendSmsRequestUsesJsonParams() {
        val line = EngineProtocolCodec.request(20, "sendSms", SmsProtocolCodec.params(SYNTH_NUMBER, SYNTH_TEXT))
        val message = Json.parseToJsonElement(line.trimEnd()).jsonObject
        val params = message.getValue("params").jsonObject

        assertEquals("sendSms", message.getValue("method").jsonPrimitive.content)
        assertEquals(SYNTH_NUMBER, params.getValue("number").jsonPrimitive.content)
        assertEquals(SYNTH_TEXT, params.getValue("text").jsonPrimitive.content)
    }

    private fun decodeEvent(line: String): SmsWireEvent? {
        val frame = EngineProtocolCodec.decode(line)
        return SmsProtocolCodec.decodeEvent(assertIs<EngineProtocolFrame.Event>(frame))
    }
}

private const val SYNTH_NUMBER = "+155501"
private const val SYNTH_TEXT = "synth-one"
private const val MO_ID = "mo-synth-1"
private const val MT_ID = "mt-synth-1"
