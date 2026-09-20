package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFormatException
import com.vowifi.sw.manager.model.OperationalLogMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationalLogShellCodecTest {
    @Test
    fun decodesPlannedListEnvelopeAndIgnoresUnknownKeys() {
        val list = OperationalLogShellCodec.list(
            """{"ok":true,"rotatedCount":1,"files":[{"id":"f1","name":"engine.log","suffix":"1234"},{"id":"f32","name":"launcher.log","suffix":"5678"}],"future":true}""",
        )

        assertEquals(1, list.rotatedCount)
        assertEquals(2, list.files.size)
        assertEquals("f1", list.files[0].id)
        assertEquals("engine.log", list.files[0].name)
        assertEquals("1234", list.files[0].suffix)
        assertEquals("launcher.log", list.files[1].name)
    }

    @Test
    fun decodesPlannedTailEnvelopeWithoutExposingRawFileBytes() {
        val tail = OperationalLogShellCodec.tail(
            """{"ok":true,"id":"f1","truncated":true,"lines":["2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] engine started"],"future":true}""",
        )

        assertTrue(tail.truncated)
        assertEquals(1, tail.lines.size)
        assertEquals("2026-08-17 12:34:56.789", tail.lines.single().timestamp)
        assertEquals("实例 •••• 1234", tail.lines.single().displayLabel)
        assertPrivate(tail.lines.single().displayLabel)
        assertPrivate(tail.lines.single().message.name)
    }

    @Test
    fun acceptsSafeProtocolWordsAfterSensitiveValuesWereRedacted() {
        val tail = OperationalLogShellCodec.tail(
            """{"ok":true,"id":"f1","truncated":false,"lines":["2026-08-17 12:34:56.789 [WARN] [ims] [•••• 1234] SIP transport unavailable [identity] [secret] [path]"]}""",
        )

        assertEquals(1, tail.lines.size)
        assertEquals(OperationalLogMessage.NEEDS_ATTENTION, tail.lines.single().message)
        assertPrivate(OperationalLogExporter.utf8(tail.lines))
    }

    @Test
    fun acceptsExactWirePlaceholdersWithoutExposingThemToUiCopy() {
        val tail = OperationalLogShellCodec.tail(
            """{"ok":true,"id":"f32","truncated":false,"lines":["2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] [identity] [secret] [path]"]}""",
        )

        val entry = tail.lines.single()
        assertEquals(OperationalLogMessage.EVENT, entry.message)
        assertEquals("敏感信息已隐藏", entry.safeSummary)
        val exported = OperationalLogExporter.utf8(tail.lines)
        assertFalse(exported.contains("[identity]"))
        assertFalse(exported.contains("[secret]"))
        assertFalse(exported.contains("[path]"))
        assertPrivate(exported)
    }

    @Test
    fun acceptsWireLinesWithNetworkAddressesAndBoundsThemDuringParsing() {
        val tail = OperationalLogShellCodec.tail(
            """{"ok":true,"id":"f1","truncated":false,"lines":["2026-08-17 12:34:56.789 [INFO] [tunnel] [•••• 1234] pcscf=2001:db8::1 remote=[2001:db8::2]:5060"]}""",
        )

        assertEquals(1, tail.lines.size)
        val entry = tail.lines.single()
        assertFalse(entry.safeSummary.contains("2001:db8::1"))
        assertFalse(entry.safeSummary.contains("2001:db8::2"))
        assertFalse(entry.safeSummary.contains("[identity]"))
        assertFalse(entry.safeSummary.contains("[secret]"))
        assertFalse(entry.safeSummary.contains("[path]"))
        assertFalse(OperationalLogRedactor.requiresRedaction(entry.safeSummary))
    }

    @Test
    fun rejectsUnsuccessfulOrIncompleteEnvelopes() {
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.list("""{"ok":false,"code":"UNAVAILABLE"}""")
        }
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.list("""{"ok":true}""")
        }
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.tail("""{"ok":true,"truncated":false}""")
        }
    }

    @Test
    fun refusesPayloadsThatStillContainPrivateMaterial() {
        val payloads = listOf(
            tailWith("[TEST-99999999999991234] engine started"),
            tailWith("[•••• 1234] imsi=[identity]"),
            tailWith("[•••• 1234] phone=[identity]"),
            tailWith("[•••• 1234] sip:[identity]"),
            tailWith("[•••• 1234] CK=[secret]"),
            tailWith("[•••• 1234] imsi="),
            tailWith("[•••• 1234] sip:"),
            tailWith("[•••• 1234] password:"),
            tailWith("[•••• 1234] token = [secret]"),
            tailWith("[•••• 1234] certificate:[secret]"),
            tailWith("[•••• 1234] identity"),
            tailWith("[•••• 1234] [Identity]"),
            tailWith("[•••• 1234] secret"),
            tailWith("[•••• 1234] 001010999999999"),
            tailWith("[•••• 1234] 0123456789abcdef0123456789abcdef"),
            tailWith("[•••• 1234] read /data/adb/test-root"),
            tailWith("[•••• 1234] read /proc/9999/cmdline"),
            tailWith("[•••• 1234] sms_body=hello from subscriber tonight"),
            tailWith("[•••• 1234] dial +855 (12) 345 678"),
            tailWith("[•••• 1234] remote=sip:user@example.invalid"),
        )

        payloads.forEach { payload ->
            assertFailsWith<OperationalLogFormatException> {
                OperationalLogShellCodec.tail(payload)
            }
        }
    }

    @Test
    fun rejectsIdsOutsideExactOrdinalContract() {
        val invalidIds = listOf(
            "e.1",
            "l.1",
            "f0",
            "f33",
            "f01",
            "F1",
            "generic-token",
            "f111111111111111111111111111111111",
        )

        invalidIds.forEach { id ->
            assertFailsWith<OperationalLogFormatException>("list accepted $id") {
                OperationalLogShellCodec.list(
                    """{"ok":true,"rotatedCount":0,"files":[{"id":"$id","name":"engine.log","suffix":"1234"}]}""",
                )
            }
            assertFailsWith<OperationalLogFormatException>("tail accepted $id") {
                OperationalLogShellCodec.tail(
                    """{"ok":true,"id":"$id","truncated":false,"lines":[]}""",
                )
            }
        }
    }
}

private fun tailWith(suffix: String): String =
    """{"ok":true,"id":"f1","truncated":false,"lines":["2026-08-17 12:34:56.789 [INFO] [engine] $suffix"]}"""
