package com.vowifi.sw.manager.data

import kotlinx.serialization.json.*
import kotlin.test.*
import com.vowifi.sw.manager.model.ConfigurationFormatException

class ConfigurationMediaSecurityCodecTest {
    private val group = """{"speechFormats":[{"codec":"pcmu","framing":"sample","payloadType":0}],"security":{"keying":"sdes","suites":["AES_CM_128_HMAC_SHA1_80","AEAD_AES_256_GCM","AEAD_AES_128_GCM"]}}"""

    @Test fun securityIsAnOrderedNonSecretPartOfTheCompleteMediaGroup() {
        val raw = Json.parseToJsonElement(group)
        val decoded = ConfigurationCallMediaCodec.decode(raw)
        assertEquals(raw, ConfigurationCallMediaCodec.encode(decoded))
        assertFalse(decoded.toString().contains("sdes"))
        for (security in listOf("null", "{}", """{"keying":"sdes","suites":[]}""",
            """{"keying":"unknown","suites":["AES_CM_128_HMAC_SHA1_80"]}""",
            """{"keying":"sdes","suites":["unknown"]}""",
            """{"keying":"sdes","suites":["AEAD_AES_128_GCM","AEAD_AES_128_GCM"]}""",
            """{"keying":"sdes","suites":["AES_CM_128_HMAC_SHA1_80"],"masterKey":"synthetic"}""",
            """{"keying":"sdes","suites":["AES_CM_128_HMAC_SHA1_80"],"allowPlaintext":true}""")) {
            val invalid = JsonObject(raw.jsonObject + ("security" to Json.parseToJsonElement(security)))
            assertFailsWith<ConfigurationFormatException> { ConfigurationCallMediaCodec.decode(invalid) }
        }
        val plain = JsonObject(raw.jsonObject - "security")
        assertEquals(plain, ConfigurationCallMediaCodec.encode(ConfigurationCallMediaCodec.decode(plain)))
    }

    @Test fun liveProtectionIsTypedWithoutKeysAndOldMissingFactsStayUnknown() {
        val media = Json.parseToJsonElement("""{"epoch":"media-test","revision":1,"codec":"pcmu","framing":"sample","clockRate":8000,"payloadType":0,"receivePayloadType":0,"sendPtimeMs":20,"receiveMaxPtimeMs":240,"direction":"sendrecv","inactivityTimeoutMs":0,"quality":{"revision":0},"security":{"keying":"sdes","suite":"AEAD_AES_256_GCM"}}""").jsonObject
        ConfigurationRuntimeMediaCodec.decode(media)
        for (security in listOf("null", "{}", """{"keying":"none","suite":"AEAD_AES_256_GCM"}""",
            """{"keying":"sdes","suite":"unknown"}""", """{"keying":"sdes","suite":"AEAD_AES_256_GCM","masterSalt":"synthetic"}""")) {
            assertFailsWith<ConfigurationFormatException> {
                ConfigurationRuntimeMediaCodec.decode(JsonObject(media + ("security" to Json.parseToJsonElement(security))))
            }
        }
        ConfigurationRuntimeMediaCodec.decode(JsonObject(media + ("security" to buildJsonObject { put("keying", "none") })))
        ConfigurationRuntimeMediaCodec.decode(JsonObject(media - "security"))
    }
}
