@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.runEngineControlProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class R5EngineStartBudgetHostTest {
    @Test fun startupBudgetCoversTheBoundedModuleIdentityTransaction() = runBlocking {
        val process = R5CommandProcess()
        var invocation = ""
        runEngineControlProcess("start", "0000000000000000001") { command -> invocation = command; process }
        assertTrue(invocation.endsWith(" start 0000000000000000001"))
        assertTrue("App killed a still-bounded identity/start transaction", process.waitSeconds in 70L..90L)
        assertEquals(1, process.waitCalls)
        assertFalse(process.destroyed)
    }

    @Test fun ordinaryReadsAndStopsKeepTheExistingTwentySecondBudget() = runBlocking {
        for (command in listOf("list", "stop")) {
            val process = R5CommandProcess()
            runEngineControlProcess(command, if (command == "list") null else "0000000000000000001") { process }
            assertEquals(20L, process.waitSeconds)
            assertEquals(1, process.waitCalls)
        }
    }

    @Test fun exceededStartupDeadlineDestroysTheProcessAndDoesNotRetry() = runBlocking {
        val process = R5CommandProcess(finishes = false)
        var failed = false
        try { runEngineControlProcess("start", "0000000000000000001") { process } }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertTrue(process.destroyed)
        assertEquals(1, process.waitCalls)
    }
}

private class R5CommandProcess(private val finishes: Boolean = true) : Process() {
    var waitSeconds = 0L
    var waitCalls = 0
    var destroyed = false
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream("{\"ok\":true}".toByteArray())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor(): Int = 0
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { waitCalls++; waitSeconds = unit.toSeconds(timeout); return finishes }
    override fun exitValue(): Int = 0
    override fun destroy() { destroyed = true }
    override fun destroyForcibly(): Process { destroyed = true; return this }
}
