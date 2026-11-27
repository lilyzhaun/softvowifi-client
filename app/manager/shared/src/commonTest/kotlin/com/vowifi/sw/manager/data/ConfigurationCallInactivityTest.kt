package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationCallInactivityTest {
    private val speech = """"speechFormats":[{"codec":"amr-wb","framing":"oa"}]"""
    @Test fun explicitZeroAndProjectUpperBoundRoundTripWithoutBecomingDefaults() {
        for (value in listOf(0, 160, 86400000)) {
            val raw = Json.parseToJsonElement("{$speech,\"inactivity\":{\"timeoutMs\":$value}}")
            assertEquals(raw, ConfigurationCallMediaCodec.encode(ConfigurationCallMediaCodec.decode(raw)))
        }
        val absent = Json.parseToJsonElement("{$speech}")
        assertEquals(absent, ConfigurationCallMediaCodec.encode(ConfigurationCallMediaCodec.decode(absent)))
    }
    @Test fun malformedAndPartialObjectsNeverBecomeEditableKnownChoices() {
        for (value in listOf("null", "{}", "{\"timeoutMs\":null}", "{\"timeoutMs\":-1}", "{\"timeoutMs\":86400001}",
            "{\"timeoutMs\":1.0}", "{\"timeoutMs\":\"160\"}", "{\"timeoutMs\":true}", "{\"timeoutMs\":160,\"action\":\"ignore\"}")) {
            assertFailsWith<ConfigurationFormatException.Unparseable> {
                ConfigurationCallMediaCodec.decode(Json.parseToJsonElement("{$speech,\"inactivity\":$value}"))
            }
        }
        assertFailsWith<ConfigurationFormatException.Unparseable> {
            ConfigurationCallMediaCodec.decode(Json.parseToJsonElement("""{"inactivity":{"timeoutMs":160}}"""))
        }
    }
    @Test fun wholeCatalogAndUserFactsRetainPolicyButNeverMaterializeItInDefaultFacts() {
        val value = Json.parseToJsonElement("{$speech,\"inactivity\":{\"timeoutMs\":0}}")
        val catalog = buildJsonObject { put("state", "known"); put("source", "catalog"); put("value", value) }
        val user = buildJsonObject { put("state", "known"); put("source", "user"); put("value", value); put("override", value) }
        assertEquals(ConfigurationCallMediaCodec.decode(value), ConfigurationCallMediaCodec.facts(catalog)!!.value)
        assertEquals(ConfigurationCallMediaCodec.decode(value), ConfigurationCallMediaCodec.facts(user)!!.overrideValue)
        val default = ConfigurationCallMediaCodec.facts(Json.parseToJsonElement("""{"state":"known","source":"default","baseline":"amr-compatible-v1"}"""))!!
        assertNull(default.value)
        assertNull(default.overrideValue)
    }
}
