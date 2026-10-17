package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogFormatException
import com.vowifi.sw.manager.model.OperationalLogOrigin
import com.vowifi.sw.manager.model.OperationalLogTail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** U4: exercise existing codec APIs, not an unimplemented parser method or UI fixture. */
class StartupOperationalLogCodecTest {
    private val key = "a".repeat(64)
    private val startup = OperationalLogFile(key, "launcher.log", "0001", "b".repeat(64))
    private val engine = startup.copy(name = "engine.log")
    private val standard = "2026-10-16 12:34:56.789 [INFO] [engine] [•••• 0001] engine started"

    @Test fun verifiedStartupContextPreservesKnownErrorsWithoutManufacturingTimes() {
        val lines = listOf("错误: --iccid 必填", "错误: 初始化日志失败: permission denied",
            "错误: 初始化日志失败: open [path] permission denied")
        val parsed = OperationalLogShellCodec.read(envelope(lines), startup)
        assertEquals(lines.size, parsed.lines.size, "已知安全stderr必须成为实际entry而非只留issue")
        assertTrue(parsed.issues.isEmpty())
        parsed.lines.forEach {
            assertEquals("时间未知", it.timestamp); assertEquals(OperationalLogOrigin.STARTUP, it.origin)
            assertTrue(it.safeSummary.isNotBlank()); assertTrue(it.safeDetail.isNotBlank())
            assertFalse(OperationalLogRedactor.requiresRedaction(it.safeSummary))
            assertFalse(OperationalLogRedactor.requiresRedaction(it.safeDetail))
        }
    }

    @Test fun standardLauncherLinesKeepTheirRealTimeButCarryTheirVerifiedOrigin() {
        val row = OperationalLogShellCodec.read(envelope(listOf(standard)), startup).lines.single()
        assertEquals("2026-10-16 12:34:56.789", row.timestamp)
        assertEquals(OperationalLogOrigin.STARTUP, row.origin)
        assertEquals("engine started", row.safeSummary)
        assertEquals(OperationalLogOrigin.ENGINE,
            OperationalLogShellCodec.read(envelope(listOf(standard))).lines.single().origin)
    }

    @Test fun legacyTailCanUseItsVerifiedLauncherContextWithoutChangingOrdinalIdentity() {
        val file = startup.copy(id = "f1", groupKey = null)
        val parsed = OperationalLogShellCodec.tail(envelope(listOf("错误: --iccid 必填"), legacy = true), file)
        assertEquals("f1", parsed.id); assertEquals(1, parsed.lines.size)
        assertEquals(OperationalLogOrigin.STARTUP, parsed.lines.single().origin)
        assertEquals("时间未知", parsed.lines.single().timestamp)
    }

    @Test fun engineAndMissingContextStayStrictForExactlyTheSameUnstructuredLine() {
        val output = envelope(listOf("错误: --iccid 必填"))
        for (context in listOf(engine, null)) {
            val parsed = OperationalLogShellCodec.read(output, context)
            assertTrue(parsed.lines.isEmpty()); assertEquals(1, parsed.issues.size)
        }
        val legacy = OperationalLogShellCodec.tail(envelope(listOf("错误: --iccid 必填"), legacy = true))
        assertTrue(legacy.lines.isEmpty()); assertEquals(1, legacy.issues.size)
    }

    @Test fun unknownAndInjectedDynamicErrorTailsCannotBecomeVisibleStartupText() {
        val injections = listOf("9".repeat(15), "9".repeat(18), "9".repeat(20), "15550123456",
            "MSISDN=+15550123456", "sip:15550123456@ims.invalid", "tel:+15550123456",
            "password=synthetic-value", "token=synthetic-value", "api_key=synthetic-value",
            "user@ims.invalid", "/data/adb/synthetic", "/proc/9999/cmdline", "/dev/synthetic",
            "sms_body=synthetic message", "authorization=synthetic bearer", "unclassified synthetic prose")
        for (injection in injections) {
            val output = envelope(listOf("错误: 初始化日志失败: permission denied $injection"))
            val parsed: OperationalLogTail = try { OperationalLogShellCodec.read(output, startup) }
                catch (_: OperationalLogFormatException) { continue }
            assertTrue(parsed.lines.isEmpty(), "未知动态stderr不得经root已脱敏的假设进入UI")
            assertTrue(parsed.issues.isNotEmpty())
            assertTrue(parsed.issues.all { it.message == "日志格式无法解析" })
        }
    }

    @Test fun emptyAndBoundedStartupTailsRetainTheExistingWindowContract() {
        val empty = OperationalLogShellCodec.read(envelope(emptyList()), startup)
        assertTrue(empty.lines.isEmpty()); assertTrue(empty.issues.isEmpty()); assertFalse(empty.truncated)
        val parsed = OperationalLogShellCodec.read(envelope(List(51) { "错误: --iccid 必填" }), startup)
        assertTrue(parsed.truncated); assertEquals(50, parsed.lines.size)
    }

    @Test fun contextMustMatchTheResponseIdentityAndValidatedCatalogFields() {
        val output = envelope(listOf("错误: --iccid 必填"))
        for (file in listOf(startup.copy(id = "c".repeat(64)), startup.copy(name = "../launcher.log"),
            startup.copy(suffix = "0001x"), startup.copy(groupKey = "not-a-group"))) {
            assertFailsWith<OperationalLogFormatException> { OperationalLogShellCodec.read(output, file) }
        }
        assertFailsWith<OperationalLogFormatException> {
            OperationalLogShellCodec.tail(envelope(listOf("错误: --iccid 必填"), legacy = true), startup)
        }
    }

    @Test fun oneJsonLineCannotSmuggleAdditionalStartupRecordsThroughControlCharacters() {
        for (line in listOf("错误: --iccid 必填\n错误: --iccid 必填", "错误: --iccid 必填\r", "错误: --iccid 必填\t")) {
            val parsed = OperationalLogShellCodec.read(envelope(listOf(line)), startup)
            assertTrue(parsed.lines.isEmpty()); assertEquals(1, parsed.issues.size)
            assertEquals("日志格式无法解析", parsed.issues.single().message)
        }
    }

    private fun envelope(lines: List<String>, legacy: Boolean = false): String = buildJsonObject {
        put("ok", true); put(if (legacy) "id" else "key", if (legacy) "f1" else key)
        put("truncated", false); put("lines", JsonArray(lines.map { JsonPrimitive(it) }))
    }.toString()
}
