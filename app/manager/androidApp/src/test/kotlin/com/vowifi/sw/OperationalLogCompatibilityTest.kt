@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.LogCommand
import com.vowifi.sw.manager.data.OperationalLogReader
import com.vowifi.sw.manager.data.LogReadFailure
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OperationalLogCompatibilityTest {
    @Test fun onlyTypedUsageFallsBackAndLegacySwitchNeverUsesCache() = runBlocking {
        val commands = mutableListOf<String>()
        val source = AndroidOperationalLogSource(LogCommand { text ->
            synchronized(commands) { commands += text.substringAfter(" logs ") }
            when {
                text.endsWith("catalog") -> result("""{"ok":false,"code":"USAGE","message":"unsupported"}""", 64)
                text.endsWith("list") -> result("""{"ok":true,"rotatedCount":0,"files":[{"id":"f1","name":"engine.log","suffix":"0001"},{"id":"f2","name":"launcher.log","suffix":"0002"}]}""")
                else -> result("""{"ok":true,"id":"${text.substringAfterLast(' ')}","truncated":false,"lines":[]}""")
            }
        }, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            suspend fun awaitRead(id: String): OperationalLogUiState.Ready = withTimeout(3000) {
                source.state.first { it is OperationalLogUiState.Ready && it.selectedFileId == id && !it.loadInfo.reading } as OperationalLogUiState.Ready
            }
            assertTrue(awaitRead("f1").loadInfo.legacy)
            source.selectFile("f2")
            awaitRead("f2")
            source.selectFile("f1")
            assertFalse(awaitRead("f1").loadInfo.cached)
            assertEquals(listOf("catalog", "list", "tail f1", "tail f2", "tail f1"), synchronized(commands) { commands.toList() })
        } finally { source.close() }
    }

    @Test fun permissionMalformedAndUnavailableDoNotFallBack() = runBlocking {
        for (output in listOf(
            """{"ok":false,"code":"PERMISSION_DENIED","message":"denied"}""",
            """{"ok":false,"code":"UNAVAILABLE","message":"unavailable"}""",
            "unknown command", "{malformed", """{"ok":true,"files":[]}""",
        )) {
            var count = 0
            try {
                OperationalLogReader(LogCommand { count++; result(output) }).catalog()
                fail("invalid catalog accepted")
            } catch (_: LogReadFailure) { assertEquals(1, count) }
        }
    }

    @Test fun mismatchedIdentityIsNotAcceptedAndTimeoutIsDistinct() = runBlocking {
        val key = "a".repeat(64)
        try {
            OperationalLogReader(LogCommand { result("""{"ok":true,"key":"${"b".repeat(64)}","truncated":false,"lines":[]}""") }).read(key, false)
            fail("mismatched identity accepted")
        } catch (error: LogReadFailure) { assertEquals("日志身份不匹配", error.safeMessage) }
        try {
            OperationalLogReader(LogCommand { AndroidSuResult(true, -1, "", false) }).read(key, false)
            fail("timeout accepted")
        } catch (error: LogReadFailure) { assertEquals("日志读取超时", error.safeMessage) }
    }
    private fun result(output: String, exit: Int = 0): AndroidSuResult = AndroidSuResult(false, exit, output, false)
}
