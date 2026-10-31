package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EngineProtocolCodecTest {
    @Test fun connectionProgressEventsAreTypedBoundedAndDoNotEchoUnknownSteps() {
        fun event(sequence: Long, tunnel: String = "CONNECTING", step: String = "SIM_AKA_COMPLETE") =
            EngineProtocolFrame.Event("connectionProgress", kotlinx.serialization.json.buildJsonObject {
                put("sequence", sequence); put("tunnel", tunnel); put("ims", "REGISTERING")
                put("tunnelProgress", step); put("imsProgress", "SENDING_REGISTER")
            })
        val parsed = kotlin.test.assertNotNull(EngineProtocolCodec.connectionProgress(event(1)))
        assertEquals("SIM_AKA_COMPLETE", parsed.tunnelProgress)
        assertEquals(null, EngineProtocolCodec.connectionProgress(event(0)))
        assertEquals(null, EngineProtocolCodec.connectionProgress(event(2, tunnel = "arbitrary")))
        assertEquals("", EngineProtocolCodec.connectionProgress(event(3, step = "/data/private"))?.tunnelProgress)
    }
    @Test
    fun requestUsesJsonLinesEnvelope() {
        val line = EngineProtocolCodec.request(7, "getStatus")

        val message = Json.parseToJsonElement(line.trimEnd()).jsonObject
        assertEquals("7", message.getValue("id").jsonPrimitive.content)
        assertEquals("getStatus", message.getValue("method").jsonPrimitive.content)
        assertEquals(emptyMap(), message.getValue("params").jsonObject)
        assertEquals('\n', line.last())
    }

    @Test
    fun requestIncludesJsonParams() {
        val line = EngineProtocolCodec.request(
            20,
            "sendSms",
            kotlinx.serialization.json.buildJsonObject {
                put("number", SYNTH_NUMBER)
                put("text", SYNTH_TEXT)
            },
        )

        val message = Json.parseToJsonElement(line.trimEnd()).jsonObject
        val params = message.getValue("params").jsonObject
        assertEquals("20", message.getValue("id").jsonPrimitive.content)
        assertEquals("sendSms", message.getValue("method").jsonPrimitive.content)
        assertEquals(SYNTH_NUMBER, params.getValue("number").jsonPrimitive.content)
        assertEquals(SYNTH_TEXT, params.getValue("text").jsonPrimitive.content)
        assertEquals('\n', line.last())
    }

    @Test
    fun decodesTypedStatusAndIgnoresUnknownFields() {
        val frame = EngineProtocolCodec.decode(
            """{"id":7,"result":{"tunnel":"CONNECTED","tunnelDesc":"ready","reconnects":2,"ims":"REGISTERED","call":"IDLE","transport":"UDP","pcscf":["10.0.0.1"],"future":true}}""",
        )

        val response = assertIs<EngineProtocolFrame.Response>(frame)
        assertEquals(7, response.id)
        val status = EngineProtocolCodec.status(response)
        assertEquals("CONNECTED", status.tunnel)
        assertEquals("REGISTERED", status.ims)
        assertEquals(2, status.reconnects)
        assertEquals(listOf("10.0.0.1"), status.pcscf)
    }

    @Test
    fun preservesStructuredProtocolError() {
        val frame = EngineProtocolCodec.decode(
            """{"id":9,"error":{"code":1001,"message":"bad params"}}""",
        )

        val response = assertIs<EngineProtocolFrame.Response>(frame)
        assertEquals(1001, response.error?.code)
        assertEquals("bad params", response.error?.message)
    }

    @Test
    fun distinguishesEventsFromResponses() {
        val frame = EngineProtocolCodec.decode(
            """{"event":"tunnelState","data":{"state":"CONNECTED"}}""",
        )

        val event = assertIs<EngineProtocolFrame.Event>(frame)
        assertEquals("tunnelState", event.name)
        assertEquals("CONNECTED", event.data.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun preservesLargeRequestId() {
        val id = Long.MAX_VALUE

        val response = assertIs<EngineProtocolFrame.Response>(
            EngineProtocolCodec.decode("""{"id":$id,"result":{}}"""),
        )

        assertEquals(id, response.id)
    }

    @Test
    fun rejectsTruncatedFrame() {
        assertFails {
            EngineProtocolCodec.decode("""{"id":7,"result":{"tunnel":"CONNECTED"}""")
        }
    }

    @Test
    fun helloParamsCarriesCurrentProtocolVersion() {
        val params = EngineProtocolCodec.helloParams()
        assertEquals("1", params.getValue("version").jsonPrimitive.content)
        assertEquals(EngineProtocolCodec.PROTOCOL_VERSION, 1)
        assertEquals(EngineProtocolCodec.ERROR_VERSION_MISMATCH, 1000)
    }

    @Test
    fun helloVersionExtractsResultVersion() {
        val response = assertIs<EngineProtocolFrame.Response>(
            EngineProtocolCodec.decode("""{"id":1,"result":{"version":1}}"""),
        )

        assertEquals(1, EngineProtocolCodec.helloVersion(response))
    }

    @Test
    fun helloVersionRejectsMissingVersion() {
        val response = assertIs<EngineProtocolFrame.Response>(
            EngineProtocolCodec.decode("""{"id":1,"result":{}}"""),
        )

        assertFails { EngineProtocolCodec.helloVersion(response) }
    }
}

private const val SYNTH_NUMBER = "+155501"
private const val SYNTH_TEXT = "synth-one"
