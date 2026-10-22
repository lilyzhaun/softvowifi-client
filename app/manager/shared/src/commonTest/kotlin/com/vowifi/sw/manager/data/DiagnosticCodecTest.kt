package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class DiagnosticCodecTest {
    private fun sample() = DiagnosticRecord(DiagnosticEvent(
        DiagnosticComponent.SUBSCRIPTIONS, DiagnosticLevel.ERROR, DiagnosticName.REJECTED,
        DiagnosticStage.NORMALIZE, DiagnosticResult.FAILURE, DiagnosticReason.UNSUPPORTED_CHARACTERS, slot = 1,
    ), "f47ac10b-58cc-4372-a567-0e02b2c3d479", 123, 1_792_000_000_000, 42)

    @Test fun fixedSchemaRoundTripsAndCannotContainFreeText() {
        val event = sample()
        val bytes = event.bytes()
        assertTrue(bytes.size <= 2048)
        assertEquals(event, DiagnosticCodec.parse(bytes.decodeToString().trimEnd()))
        assertTrue(bytes.decodeToString().contains("unsupported_characters"))
        assertEquals("SIM 订阅 · 身份规范化：失败，身份包含不支持的字符", DiagnosticLabels.summary(event.event))
    }

    @Test fun unknownVersionFieldsEnumsAndIdentityLikeValuesNeverReachProjection() {
        val base = Json.parseToJsonElement(sample().bytes().decodeToString()).jsonObject
        val malicious = listOf(
            "ICCID=TEST-MIXED-identity", "iMsI : TEST-private", "IMPI=sip:user@example.invalid",
            "MSISDN: TEST-number", "token=TEST-only-canary", "first\nsecond\n[ENGINE] fake", "x".repeat(5000),
        )
        for (payload in malicious) for (field in listOf("run", "component", "reason", "wall_ms", "count", "text")) {
            val wire = buildJsonObject { base.forEach { (key, value) -> put(key, value) }; put(field, payload) }.toString()
            assertNull(DiagnosticCodec.parse(wire), "accepted untrusted field $field")
        }
        assertNull(DiagnosticCodec.parse(buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }; put("v", 2)
        }.toString()))
        assertNull(DiagnosticCodec.parse("not JSON\n[APP] injected"))
    }

    @Test fun oversizedNumericValuesCannotSmuggleAnIdentityAndInvalidSlotsAreRejected() {
        val buffer = DiagnosticBuffer(sample().run, 123)
        assertTrue(!buffer.offer(sample().event.copy(slot = 2), 1000, 0))
        assertTrue(!buffer.offer(sample().event.copy(count = -1), 1000, 0))
        assertTrue(!buffer.offer(sample().event.copy(networkGeneration = Long.MAX_VALUE), 1000, 0))
        assertEquals(0, buffer.stats().queued)
        assertEquals(3, buffer.stats().dropped)
        val base = Json.parseToJsonElement(sample().bytes().decodeToString()).jsonObject
        for (field in listOf("slot", "attempt", "count", "accepted", "network_generation", "suppressed")) {
            assertNull(DiagnosticCodec.parse(buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }; put(field, JsonPrimitive(Long.MAX_VALUE))
            }.toString()))
        }
        assertNotNull(DiagnosticCodec.parse(sample().bytes().decodeToString()))
    }
}
