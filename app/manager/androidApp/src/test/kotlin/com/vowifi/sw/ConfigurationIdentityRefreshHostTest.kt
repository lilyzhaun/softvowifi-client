@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidConfigurationCommands
import com.vowifi.sw.manager.data.AndroidSuCommand
import com.vowifi.sw.manager.data.CommandRead
import com.vowifi.sw.manager.model.ConfigurationUiState
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConfigurationIdentityRefreshHostTest {
    private val iccid = "0000000000000000001"
    private val payload = """{"iccid":"$iccid","slot":0,"imsi":"001020000000002"}"""

    @Test fun freshIdentityUsesStdinAndTheExplicitModuleEntry() = runBlocking {
        val input = Files.createTempFile("identity-refresh-", ".json").toFile()
        try {
            var requested = ""
            val commands = AndroidConfigurationCommands(AndroidSuCommand(start = { command ->
                requested = command
                ProcessBuilder("sh", "-c", "cat > '${input.path}'; printf '%s' '{\"ok\":true,\"code\":\"APPLIED\",\"message\":\"config applied\"}'").start()
            }))
            val result = commands.refreshIdentity(iccid, 54, 0, payload)
            assertTrue(result is CommandRead.Listed)
            assertEquals("/data/adb/modules/vowifi_sw/control.sh config refresh-identity $iccid 54 0", requested)
            assertFalse(requested.contains("001020000000002"))
            assertFalse(requested.contains('{'))
            assertEquals(payload, input.readText())
        } finally {
            input.delete()
        }
    }

    @Test fun aRunningTargetIsReportedAndNeverRetried() = runBlocking {
        var starts = 0
        val commands = AndroidConfigurationCommands(AndroidSuCommand(start = {
            starts++
            ProcessBuilder("sh", "-c", "cat >/dev/null; printf '%s' '{\"ok\":false,\"code\":\"TARGET_RUNNING\",\"message\":\"target is running\"}'; exit 1").start()
        }))
        val result = commands.refreshIdentity(iccid, 54, 0, payload) as CommandRead.Mapped
        assertEquals("请先停止该 SIM 的引擎，再刷新身份", (result.state as ConfigurationUiState.Failure).message)
        assertEquals(1, starts)
    }

    @Test fun nonzeroExitCannotClaimTheIdentityWasApplied() = runBlocking {
        val commands = AndroidConfigurationCommands(AndroidSuCommand(start = {
            ProcessBuilder("sh", "-c", "cat >/dev/null; printf '%s' '{\"ok\":true,\"code\":\"APPLIED\",\"message\":\"config applied\"}'; exit 1").start()
        }))
        val result = commands.refreshIdentity(iccid, 54, 0, payload)
        assertTrue(result is CommandRead.Mapped && result.state is ConfigurationUiState.Failure)
    }
}
