package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationCallMediaCodecTest {
    @Test fun pcmuSampleGroupPreservesStaticZeroAndRejectsAmrOnlyParameters() {
        val raw = """{"speechFormats":[{"codec":"pcmu","framing":"sample","payloadType":0}]}"""
        val parsed = ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(raw))
        assertEquals("pcmu", parsed.speechFormats.single().codec.wire)
        assertEquals("sample", parsed.speechFormats.single().framing.wire)
        assertEquals(0, parsed.speechFormats.single().payloadType)
        assertEquals(Json.parseToJsonElement(raw), ConfigurationCallMediaCodec.encode(parsed))
        for (field in listOf("\"modeSet\":[]", "\"modeSet\":[0]", "\"initialMode\":0",
            "\"receiveModeChangePeriod\":1", "\"receiveModeChangeNeighbor\":false")) {
            val invalid = """{"speechFormats":[{"codec":"pcmu","framing":"sample",$field}]}"""
            assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(invalid)) }
        }
        for (pt in listOf(1, 8, 95, 128)) {
            assertFailsWith<ConfigurationFormatException> {
                ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(raw.replace("\"payloadType\":0", "\"payloadType\":$pt")))
            }
        }
        for ((codec, framing) in listOf("pcmu" to "be", "pcmu" to "oa", "amr-nb" to "sample", "amr-wb" to "sample")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(
                """{"speechFormats":[{"codec":"$codec","framing":"$framing"}]}""")) }
        }
        val duplicate = """{"speechFormats":[{"codec":"pcmu","framing":"sample","payloadType":0},{"codec":"pcmu","framing":"sample","payloadType":110}]}"""
        assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(duplicate)) }
        val inheritedPT = ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(raw.replace(",\"payloadType\":0", "")))
        assertNull(inheritedPT.speechFormats.single().payloadType)
        assertFalse("payloadType" in ConfigurationCallMediaCodec.encode(inheritedPT).jsonObject.getValue("speechFormats").jsonArray[0].jsonObject)
    }

    @Test fun boundedMediaFactsKeepExplicitZeroAndOrderWithoutInventingDefaults() {
        val raw = """{"speechFormats":[{"codec":"amr-nb","framing":"oa","modeSet":[0,2,4],"initialMode":0,"payloadType":110},{"codec":"amr-wb","framing":"be"}]}"""
        val facts = ConfigurationFactsCodec.local(localFacts("""{"state":"known","source":"user","value":$raw,"override":$raw}"""))!!
        val media = facts.callMedia!!
        assertTrue(media.known)
        assertEquals("user", media.source)
        assertEquals(0, media.value!!.speechFormats.first().initialMode)
        assertEquals("amr-nb", media.value.speechFormats.first().codec.wire)
        assertEquals(media.value, media.overrideValue)
        assertEquals(Json.parseToJsonElement(raw), ConfigurationCallMediaCodec.encode(media.overrideValue!!))
        val inherited = ConfigurationFactsCodec.local(localFacts("""{"state":"known","source":"default","baseline":"amr-compatible-v1"}"""))!!.callMedia!!
        assertTrue(inherited.compatibilityBaseline)
        assertNull(inherited.value)
        assertNull(inherited.overrideValue)
        assertNull(ConfigurationFactsCodec.local(localFacts(null))!!.callMedia)
        assertFalse(ConfigurationFactsCodec.local(localFacts("""{"state":"unknown","source":"user"}"""))!!.callMedia!!.known)
        assertFalse(media.toString().contains("110"))
    }

    @Test fun malformedRecognizedMediaFactsCannotOverrideUnknownOrContradictProvenance() {
        val valid = """{"speechFormats":[{"codec":"amr-nb","framing":"oa","initialMode":0}]}"""
        for (raw in listOf(
            "null", "[]", """{"state":"known","source":"default"}""",
            """{"state":"known","source":"default","baseline":"other"}""",
            """{"state":"unknown","source":"user","value":$valid}""",
            """{"state":"known","source":"user","value":$valid}""",
            """{"state":"known","source":"user","value":$valid,"override":{}}""",
            """{"state":"known","source":"user","value":$valid,"override":$valid,"imsi":"private"}""",
            """{"state":"known","source":"catalog","value":$valid,"override":$valid}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationFactsCodec.local(localFacts(raw)) }
    }

    @Test fun typedMediaPolicyRejectsUnsupportedOrAmbiguousFormats() {
        for (policy in listOf(
            """{"speechFormats":[]}""", """{"speechFormats":[null]}""",
            """{"speechFormats":[{"codec":"evs","framing":"oa"}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","modeSet":"AAIE"}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","modeSet":[null]}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","modeSet":[8]}]}""",
            """{"speechFormats":[{"codec":"amr-wb","framing":"be","modeSet":[0,0]}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","modeSet":[0,2],"initialMode":1}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","initialMode":null}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","payloadType":"110"}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","payloadType":128}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa","clock":16000}]}""",
            """{"speechFormats":[{"codec":"amr-nb","framing":"oa"},{"codec":"amr-nb","framing":"oa"}]}""",
        )) assertFailsWith<ConfigurationFormatException> {
            ConfigurationCallMediaCodec.decode(Json.parseToJsonElement(policy))
        }
    }

    @Test fun independentReceiveChoicesRoundTripAndExplicitFalseIsNotInheritance() {
        val raw = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-nb","framing":"oa","receiveModeChangePeriod":2,"receiveModeChangeNeighbor":false}]}""")
        val decoded = ConfigurationCallMediaCodec.decode(raw)
        assertEquals(raw, ConfigurationCallMediaCodec.encode(decoded))
        val facts = ConfigurationFactsCodec.local(localFacts(buildJsonObject {
            put("state", "known"); put("source", "user"); put("value", raw); put("override", raw)
        }.toString()))!!
        assertEquals(raw, ConfigurationCallMediaCodec.encode(ConfigurationCallMediaCodec.read(facts)!!))
        for (fields in listOf(
            "\"receiveModeChangePeriod\":0", "\"receiveModeChangePeriod\":3",
            "\"receiveModeChangePeriod\":\"2\"", "\"receiveModeChangePeriod\":null",
            "\"receiveModeChangeNeighbor\":1", "\"receiveModeChangeNeighbor\":\"false\"", "\"receiveModeChangeNeighbor\":null",
        )) {
            val parsed = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-nb","framing":"oa",$fields}]}""")
            assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(parsed) }
        }
    }

    private fun localFacts(media: String?) = buildJsonObject {
        put("schema", 1)
        put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop")
        put("resolutionRevision", "rf1_" + "a".repeat(64))
        put("fields", buildJsonArray {})
        media?.let { put("callMedia", Json.parseToJsonElement(it)) }
    }

    @Test fun catalogMediaIsResolvedButNeverDecodedAsUserChoicesAndConflictStaysUnknown() {
        val raw = """{"speechFormats":[{"codec":"amr-nb","framing":"oa","initialMode":0,"receiveModeChangeNeighbor":false},{"codec":"amr-wb","framing":"be"}]}"""
        val facts = ConfigurationFactsCodec.local(localFacts("""{"state":"known","source":"catalog","value":$raw}"""))!!
        val group = facts.callMedia!!
        assertEquals("catalog", group.source)
        assertEquals(0, group.value!!.speechFormats.first().initialMode)
        assertEquals(false, group.value.speechFormats.first().receiveModeChangeNeighbor)
        assertNull(group.overrideValue)
        assertFalse(group.compatibilityBaseline)
        assertEquals(com.vowifi.sw.manager.model.ConfigurationCallMedia(), ConfigurationCallMediaCodec.read(facts))
        val unknown = ConfigurationFactsCodec.local(localFacts("""{"state":"unknown","source":"catalog"}"""))!!
        assertFalse(unknown.callMedia!!.known)
        assertNull(ConfigurationCallMediaCodec.read(unknown))
        for (media in listOf(
            """{"state":"known","source":"catalog"}""",
            """{"state":"known","source":"catalog","baseline":"amr-compatible-v1"}""",
            """{"state":"known","source":"catalog","value":$raw,"override":$raw}""",
            """{"state":"unknown","source":"catalog","value":$raw}""",
        )) assertFailsWith<ConfigurationFormatException> { ConfigurationFactsCodec.local(localFacts(media)) }
    }
}
