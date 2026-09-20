package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineOperationalLogParserTest {
    private val parser = EngineOperationalLogParser()

    @Test
    fun parsesExactEngineLogFormatIntoTypedRedactedEntry() {
        val result = parser.parse(
            "2026-08-17 12:34:56.789 [WARN] [tunnel] [TEST-99999999999991234] tunnel disconnected",
        )

        assertEquals(1, result.entries.size)
        assertTrue(result.issues.isEmpty())
        val entry = result.entries.single()
        assertEquals("2026-08-17 12:34:56.789", entry.timestamp)
        assertEquals(OperationalLogLevel.WARN, entry.level)
        assertEquals(OperationalLogKind.TUNNEL, entry.kind)
        assertEquals("实例 •••• 1234", entry.displayLabel)
        assertEquals(OperationalLogMessage.TUNNEL_DISCONNECTED, entry.message)
        assertEquals("tunnel disconnected", entry.safeSummary)
        assertPrivate(entry.displayLabel)
        assertPrivate(entry.safeSummary)
    }

    @Test
    fun parsesUnknownSafeInfoLineAsEventWithSafeSummary() {
        val result = parser.parse(
            "2026-08-17 12:34:56.789 [INFO] [tunnel] [TEST-99999999999991234] tunnel rekey complete",
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals(OperationalLogMessage.EVENT, entry.message)
        assertEquals("tunnel rekey complete", entry.safeSummary)
        assertFalse(entry.message == OperationalLogMessage.UNAVAILABLE)
    }

    @Test
    fun parsesChineseProductionLifecyclePhrasesIntoTypedMessageAndInferredKind() {
        data class Case(
            val line: String,
            val expectedMessage: OperationalLogMessage,
            val expectedKind: OperationalLogKind,
        )

        val cases = listOf(
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] 引擎启动 [identity]",
                OperationalLogMessage.ENGINE_STARTED,
                OperationalLogKind.ENGINE,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] 引擎退出",
                OperationalLogMessage.ENGINE_STOPPED,
                OperationalLogKind.ENGINE,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] 隧道状态: CONNECTING",
                OperationalLogMessage.TUNNEL_CONNECTING,
                OperationalLogKind.TUNNEL,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] 隧道状态: CONNECTED",
                OperationalLogMessage.TUNNEL_CONNECTED,
                OperationalLogKind.TUNNEL,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] 隧道状态: DISCONNECTED stopped",
                OperationalLogMessage.TUNNEL_DISCONNECTED,
                OperationalLogKind.TUNNEL,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] IMS 注册启动: P-CSCF=2001:db8::1 LocalIP=2001:db8::2",
                OperationalLogMessage.IMS_REGISTERING,
                OperationalLogKind.IMS,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] IMS 状态: REGISTERING",
                OperationalLogMessage.IMS_REGISTERING,
                OperationalLogKind.IMS,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] IMS 状态: REGISTERED sip:user@example.invalid",
                OperationalLogMessage.IMS_REGISTERED,
                OperationalLogKind.IMS,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] IMS 注册成功",
                OperationalLogMessage.IMS_REGISTERED,
                OperationalLogKind.IMS,
            ),
            Case(
                "2026-08-17 12:34:56.789 [INFO] [engine] [•••• 1234] IMS 状态: UNREGISTERED",
                OperationalLogMessage.IMS_UNREGISTERED,
                OperationalLogKind.IMS,
            ),
        )

        cases.forEach { (line, expectedMessage, expectedKind) ->
            val result = parser.parse(line)
            assertEquals(1, result.entries.size, line)
            val entry = result.entries.single()
            assertEquals(expectedMessage, entry.message, line)
            assertEquals(expectedKind, entry.kind, line)
            assertFalse(entry.safeSummary.contains("2001:db8::"), line)
            assertFalse(entry.safeSummary.contains("[identity]"), line)
            assertFalse(entry.safeSummary.contains("[secret]"), line)
            assertFalse(entry.safeSummary.contains("[path]"), line)
            assertFalse(OperationalLogRedactor.requiresRedaction(entry.safeSummary), line)
        }
    }

    @Test
    fun redactsSensitiveDataFromSummaryAndBoundsLength() {
        val raw = "2026-08-17 12:34:56.789 [INFO] [ims] [TEST-99999999999991234] registered remote=2001:db8::1 token=secret123 path=/data/local/tmp sms_body=hello from subscriber tonight"
        val result = parser.parse(raw)

        val entry = result.entries.single()
        assertTrue(entry.safeSummary.length <= OPERATIONAL_LOG_SAFE_SUMMARY_MAX_LENGTH)
        assertFalse(entry.safeSummary.contains("2001:db8::1"))
        assertFalse(entry.safeSummary.contains("secret123"))
        assertFalse(entry.safeSummary.contains("/data/local/tmp"))
        assertFalse(entry.safeSummary.contains("hello from subscriber tonight"))
        assertFalse(entry.safeSummary.contains("[identity]"))
        assertFalse(entry.safeSummary.contains("[secret]"))
        assertFalse(entry.safeSummary.contains("[path]"))
        assertTrue(entry.safeSummary.contains("敏感信息已隐藏"))
        assertFalse(OperationalLogRedactor.requiresRedaction(entry.safeSummary))
    }

    @Test
    fun exporterFinalColumnMatchesEntrySafeSummary() {
        val result = parser.parse(
            "2026-08-17 12:34:56.789 [INFO] [tunnel] [TEST-99999999999991234] tunnel connection established safely",
        )
        val entry = result.entries.single()
        val exported = OperationalLogExporter.utf8(result.entries)
        val lastLine = exported.lineSequence().last()
        val finalColumn = lastLine.substringAfterLast('\t')

        assertEquals(entry.safeSummary, finalColumn)
        assertEquals("tunnel connection established safely", finalColumn)
        assertFalse(exported.contains("[identity]"))
        assertFalse(exported.contains("[secret]"))
        assertFalse(exported.contains("[path]"))
    }

    @Test
    fun truncatesLongSafeMessageWithEllipsis() {
        val longMessage = "tunnel connection event message with details ".repeat(6)
        val result = parser.parse(
            "2026-08-17 12:34:56.789 [INFO] [engine] [TEST-99999999999991234] $longMessage",
        )

        val entry = result.entries.single()
        assertTrue(entry.safeSummary.length <= OPERATIONAL_LOG_SAFE_SUMMARY_MAX_LENGTH)
        assertTrue(entry.safeSummary.endsWith("…"))
    }

    @Test
    fun recordsBrokenLinesAsIssuesWithoutDiscardingValidEntries() {
        val result = parser.parse(
            """2026-08-17 12:34:56.789 [INFO] [engine] [TEST-99999999999991234] engine started
broken engine output
2026-08-17 12:34:57.000 [ERROR] [ims] [TEST-99999999999995678] ims registration failed""",
        )

        assertEquals(2, result.entries.size)
        assertEquals(1, result.issues.size)
        assertEquals(2, result.issues.single().lineNumber)
        result.entries.forEach { entry ->
            assertPrivate(entry.displayLabel)
            assertPrivate(entry.safeSummary)
        }
    }
}
