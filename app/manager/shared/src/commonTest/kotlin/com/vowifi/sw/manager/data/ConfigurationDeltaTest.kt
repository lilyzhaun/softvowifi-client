package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationDeltaTest {
    private val baseline = ConfigurationOverrides(ConfigurationTransport.WIFI, "", "", "", "8.8.8.8:53")

    @Test
    fun transportOnlyEditDoesNotClearUneditedDerivedFields() {
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(
            baseline, baseline.copy(transport = ConfigurationTransport.AUTO),
        )).jsonObject
        assertEquals(setOf("transport"), patch.keys)
        assertEquals(JsonPrimitive("auto"), patch["transport"])
    }

    @Test
    fun explicitlyClearedOverrideIsIncludedButUntouchedFieldsAreNot() {
        val original = baseline.copy(epdgFqdn = "epdg.example.org")
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(original, baseline)).jsonObject
        assertEquals(setOf("epdgFqdn"), patch.keys)
        assertEquals(JsonPrimitive(""), patch["epdgFqdn"])
        assertEquals("{}", ConfigurationCodec.encodeChanges(original, original))
    }

    @Test
    fun deltaCannotBypassExistingInputValidation() {
        assertFailsWith<ConfigurationFormatException.InvalidSmsc> {
            ConfigurationCodec.encodeChanges(baseline, baseline.copy(smsc = "invalid"))
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            ConfigurationCodec.encodeChanges(baseline, baseline.copy(dns = "11111111111111111111"))
        }
    }
}
