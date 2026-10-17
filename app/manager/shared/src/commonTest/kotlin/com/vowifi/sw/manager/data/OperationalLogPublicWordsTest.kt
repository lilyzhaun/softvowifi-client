package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFormatException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationalLogPublicWordsTest {
    @Test
    fun publicWordsSurviveBothWireContractsDisplayAndExport() {
        for (event in listOf(
            "SIM identity lost; retiring engine runtime",
            "shared secret negotiation failed",
            "identity verification completed",
        )) {
            for (keyed in listOf(false, true)) {
                val tail = decode(event, keyed)
                val entry = tail.lines.single()
                assertEquals(event, entry.safeSummary)
                assertFalse(OperationalLogRedactor.requiresWireRedaction(entry.safeSummary))
                assertTrue(OperationalLogRedactor.isPrivateUiText(entry.safeSummary))
                val exported = OperationalLogExporter.utf8(tail.lines)
                assertTrue(exported.contains(event))
                assertPrivate(exported)
            }
        }
    }

    @Test
    fun aPublicEventNeverExemptsPrivateAssignmentsOrInvalidPlaceholders() {
        val privateEvents = listOf(
            "SIM identity lost; imsi=001010999999999",
            "SIM identity lost; CK=0123456789abcdef0123456789abcdef",
            "SIM identity lost; identity=synthetic-private-value",
            "shared secret negotiation failed; secret=synthetic-private-value",
            "SIM identity lost; \"identity\":\"synthetic-private-value\"",
            "SIM identity lost;\nidentity",
            "identity", "secret", "identity secret", "[Identity]", "[SECRET]",
        )
        for (event in privateEvents) {
            for (keyed in listOf(false, true)) {
                assertFailsWith<OperationalLogFormatException> { decode(event, keyed) }
            }
        }
    }

    private fun decode(message: String, keyed: Boolean) = buildJsonObject {
        put("ok", true)
        if (keyed) put("key", "a".repeat(64)) else put("id", "f1")
        put("truncated", false)
        put("lines", buildJsonArray {
            add("2026-10-13 13:17:09.398 [WARN] [engine] [•••• 1234] $message")
        })
    }.toString().let { if (keyed) OperationalLogShellCodec.read(it) else OperationalLogShellCodec.tail(it) }
}
