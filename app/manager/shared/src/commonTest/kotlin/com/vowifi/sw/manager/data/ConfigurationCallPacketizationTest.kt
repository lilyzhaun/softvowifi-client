package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationCallMedia
import com.vowifi.sw.manager.model.ConfigurationFormatException
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationCallPacketizationTest {
    @Test fun completeReceivePairSurvivesStrictReadWriteAndOtherFormatChanges() {
        val raw = Json.parseToJsonElement(group("60", "80"))
        val policy = ConfigurationCallMediaCodec.decode(raw)
        assertEquals(raw, ConfigurationCallMediaCodec.encode(policy))
        val changed = policy.copy(speechFormats = policy.speechFormats.map { it.copy(payloadType = 110) })
        val packet = ConfigurationCallMediaCodec.encode(changed).jsonObject.getValue("packetization").jsonObject
        assertEquals(60, packet.getValue("receivePtimeMs").jsonPrimitive.int)
        assertEquals(80, packet.getValue("receiveMaxPtimeMs").jsonPrimitive.int)
        assertFalse(policy.toString().contains("60"))
    }

    @Test fun missingPairStaysAbsentAndWholeGroupClearDoesNotWriteDefaults() {
        val raw = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-wb","framing":"oa"}]}""")
        assertEquals(raw, ConfigurationCallMediaCodec.encode(ConfigurationCallMediaCodec.decode(raw)))
        assertEquals(JsonNull, ConfigurationCallMediaCodec.encode(ConfigurationCallMedia()))
        assertEquals(Json.parseToJsonElement(group("20", "25")),
            ConfigurationCallMediaCodec.encode(ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(group("20", "25")))))
    }

    @Test fun incompleteNullUnknownAndUnsupportedReceivePairsAreRejected() {
        for (pair in listOf("null", "{}", """{"receivePtimeMs":60}""",
            """{"receivePtimeMs":null,"receiveMaxPtimeMs":80}""",
            """{"receivePtimeMs":60,"receiveMaxPtimeMs":80,"extra":1}""")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(
                """{"speechFormats":[{"codec":"amr-wb","framing":"oa"}],"packetization":$pair}""")) }
        }
        for ((ptime, maximum) in listOf("0" to "80", "30" to "80", "80" to "60", "20" to "241", "20.0" to "80", "\"20\"" to "80")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(group(ptime, maximum))) }
        }
        assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(
            """{"packetization":{"receivePtimeMs":60,"receiveMaxPtimeMs":80}}""")) }
    }

    @Test fun packetizationFactsKeepTheCompleteUserGroupRatherThanInventingInheritance() {
        val raw = Json.parseToJsonElement(group("60", "80"))
        val facts = ConfigurationCallMediaCodec.facts(buildJsonObject {
            put("state", "known"); put("source", "user"); put("value", raw); put("override", raw)
        })!!
        assertEquals(facts.value, facts.overrideValue)
        assertEquals(raw, ConfigurationCallMediaCodec.encode(facts.value!!))
        val inherited = ConfigurationCallMediaCodec.facts(buildJsonObject {
            put("state", "known"); put("source", "catalog"); put("value", raw)
        })!!
        assertNull(inherited.overrideValue)
        assertEquals(raw, ConfigurationCallMediaCodec.encode(inherited.value!!))
    }

    private fun group(ptime: String, maximum: String) =
        """{"speechFormats":[{"codec":"amr-wb","framing":"oa"}],"packetization":{"receivePtimeMs":$ptime,"receiveMaxPtimeMs":$maximum}}"""
}
