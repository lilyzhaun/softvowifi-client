@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiagnosticConfigurationHostTest {
    private val key = "000000000000001"
    private val payload = """{"iccid":"$key","slot":0,"imsi":"001010000000001"}"""
    private fun commands(output: String, exit: Int, events: MutableList<DiagnosticEvent>) =
        AndroidConfigurationCommands(AndroidSuCommand(start = {
            ProcessBuilder("sh", "-c", "cat >/dev/null; printf '%s' '$output'; exit $exit").start()
        }), events::add)

    @Test fun listResultIsObservedWithoutPersistingConfigurationKeys() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val result = commands("[]", 0, events).list()
        assertTrue(result is CommandRead.Listed)
        assertTrue("real configuration list is missing its diagnostic result", events.any {
            it.stage == DiagnosticStage.CONFIG_MATCH && it.result == DiagnosticResult.SUCCESS && it.count == 0
        })
    }

    @Test fun strictIdentityMismatchSurvivesTheExistingFailureMapping() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val result = commands("""{"ok":false,"code":"IDENTITY_MISMATCH","message":"private-synthetic-message"}""", 1, events)
            .create(101, 0, payload)
        assertTrue(result is CommandRead.Mapped)
        assertTrue("strict verify failure must retain its fixed cause", events.any {
            it.stage == DiagnosticStage.STRICT_VERIFY && it.result == DiagnosticResult.FAILURE && it.reason.name == "IDENTITY_MISMATCH" && it.slot == 0
        })
        assertTrue(events.any { it.stage == DiagnosticStage.CONFIG_CREATE && it.result == DiagnosticResult.FAILURE })
        assertFalse(events.toString().contains(key))
        assertFalse(events.toString().contains("001010000000001"))
        assertFalse(events.toString().contains("private-synthetic-message"))
    }

    @Test fun identityRefreshCannotReportSuccessOnNonzeroExit() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val result = commands("""{"ok":true,"code":"APPLIED","message":"applied"}""", 1, events)
            .refreshIdentity(key, 101, 0, payload)
        assertTrue(result is CommandRead.Mapped)
        assertTrue("nonzero identity refresh must remain an observed failure", events.any {
            it.stage == DiagnosticStage.CONFIG_REFRESH && it.result == DiagnosticResult.FAILURE
        })
        assertFalse(events.any { it.result == DiagnosticResult.SUCCESS })
    }

    @Test fun confirmedCreateRecordsThePassedModuleGateNotJustCommandLaunch() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val result = commands("""{"ok":true,"code":"CREATED","message":"created"}""", 0, events).create(101, 0, payload)
        assertEquals(CommandRead.Created, result)
        assertTrue(events.any { it.stage == DiagnosticStage.CONFIG_CREATE && it.result == DiagnosticResult.STARTED })
        assertTrue(events.any { it.stage == DiagnosticStage.STRICT_VERIFY && it.result == DiagnosticResult.SUCCESS })
        assertTrue(events.any { it.stage == DiagnosticStage.CONFIG_CREATE && it.result == DiagnosticResult.SUCCESS })
    }
}
