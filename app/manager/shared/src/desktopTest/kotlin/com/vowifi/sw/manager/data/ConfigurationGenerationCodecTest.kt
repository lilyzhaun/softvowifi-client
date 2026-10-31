package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationGenerationCodecTest {
    @Test
    fun legacyAndFutureStatesAreManageableWithoutResolvingOldValues() {
        for (format in listOf("legacy", "unsupported")) {
            val snapshot = ConfigurationCodec.show(
                """{"ok":true,"formatState":"$format","configVersion":2,"lifecycle":"stopped"}""",
            )
            assertEquals("", snapshot.resolvedEpdgFqdn)
            assertEquals("", snapshot.resolvedImsRealm)
            assertEquals("", snapshot.smsc)
            assertEquals(emptyMap(), snapshot.dnsOverrides)
        }
    }

    @Test
    fun explicitCurrentStateCannotOmitVersionAndOpaqueRevision() {
        val fields = """"ok":true,"formatState":"current","displayLabel":"0001","lifecycle":"stopped","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.example.test","mcc":"310","mnc":"260""""
        for (metadata in listOf("", ",\"configVersion\":1", ",\"configVersion\":1,\"configRevision\":\"invalid\"")) {
            assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.show("{$fields$metadata}") }
        }
    }

    @Test
    fun revisionEnvelopeDoesNotTurnRequestMetadataIntoEditableConfig() {
        val before = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "")
        val revision = "r1_abcdefghijklmnopabcdefghijklmnop"
        val after = before.copy(epdgFqdn = "new.example.test", expectedRevision = revision)
        val wire = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(before, after)) as JsonObject
        assertEquals(setOf("expectedRevision", "overrides"), wire.keys)
        assertEquals(revision, wire["expectedRevision"]?.jsonPrimitive?.content)
        assertEquals(setOf("epdgFqdn"), (wire["overrides"] as JsonObject).keys)
        assertFailsWith<ConfigurationFormatException> { ConfigurationCodec.encodeRevisionChanges(before, after.copy(expectedRevision = null)) }
    }
}
