package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.data.OperationalLogExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogReadablePresentationTest {
    private val entry = OperationalLogEntry("2026-10-22 03:04:43.053", OperationalLogLevel.INFO,
        OperationalLogKind.DIAGNOSTIC, "SIM 1", OperationalLogMessage.EVENT, "已找到 SIM 1 的配置。",
        origin = OperationalLogOrigin.DIAGNOSTIC, headline = "已找到对应配置", technical = true, timeZoneLabel = "UTC")

    @Test fun specificTitleSourceAndReadableTimeReplaceTheGenericHeading() {
        assertEquals("已找到对应配置", operationalLogEntryTitle(entry))
        assertEquals("应用运行 · SIM 1", operationalLogEntryContext(entry))
        assertEquals("2026-10-22 03:04:43 UTC", operationalLogEntrySummary(entry))
        assertFalse(operationalLogEntryTitle(entry).contains("诊断事件"))
        assertFalse(isKeyLogEntry(entry))
        assertTrue(isKeyLogEntry(entry.copy(level = OperationalLogLevel.WARN)))
        assertTrue(isKeyLogEntry(entry.copy(level = OperationalLogLevel.ERROR)))
        assertEquals("2026-10-22 03:04:43.053", entry.timestamp)
    }

    @Test fun nativeAndSoftwareRegistrationRemainDifferentInDisplayAndExport() {
        val native = entry.copy(kind = OperationalLogKind.IMS, origin = OperationalLogOrigin.OBSERVER,
            headline = "系统 IMS 已注册", message = OperationalLogMessage.IMS_REGISTERED,
            safeSummary = "系统报告已通过 Wi-Fi 注册 IMS。", technical = false)
        assertEquals("系统 IMS · SIM 1", operationalLogEntryContext(native))
        assertTrue(OperationalLogExporter.utf8(listOf(native)).contains("系统 IMS"))
        assertTrue(OperationalLogExporter.utf8(listOf(native)).contains("SIM 1"))
        assertEquals("软件引擎 · SIM 1", operationalLogEntryContext(native.copy(origin = OperationalLogOrigin.ENGINE)))
    }

    @Test fun sourceNamesDescribeTheContentsAndTheTechnicalOnlyWindowIsHonest() {
        assertEquals("应用运行", operationalLogFileLabel(OperationalLogFile("app-diagnostics-v1", "app.jsonl", "")))
        assertEquals("连接过程", operationalLogFileLabel(OperationalLogFile("root-diagnostics-v1", "diagnostic.jsonl", "")))
        assertEquals("系统 IMS 注册", operationalLogFileLabel(OperationalLogFile(SYSTEM_REGISTRATION_LOG_ID, "ims-registration.jsonl", "")))
        assertEquals("最近没有关键事件", operationalLogKeyEmptySpec().title)
        assertTrue(operationalLogKeyEmptySpec().description.contains("查看全部步骤"))
    }
}
