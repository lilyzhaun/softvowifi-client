package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EnvironmentDiagnosticsFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentDiagnosticsCodecTest {
    @Test
    fun parsesHealthySnapshotAndIgnoresUnknownKeys() {
        val snapshot = EnvironmentDiagnosticsCodec.snapshot(HEALTHY_WITH_SECRETS)

        assertTrue(snapshot.module.installed)
        assertEquals("vowifi_sw", snapshot.module.id)
        assertEquals("0.3.9", snapshot.module.version)
        assertEquals(12, snapshot.module.versionCode)
        assertTrue(snapshot.ok)
        assertEquals(false, snapshot.module.markers.disable)
        assertEquals(false, snapshot.module.markers.remove)
        assertEquals(false, snapshot.module.markers.update)
        assertTrue(snapshot.root.capable)
        assertEquals(1, snapshot.config.count)
        assertTrue(snapshot.observer.packagePid)
        assertTrue(snapshot.observer.processRecord)
        assertTrue(snapshot.observer.foreground)
        assertEquals(2, snapshot.engine.instanceCount)
        assertEquals(1, snapshot.engine.runningCount)
        assertEquals(1, snapshot.engine.stoppedCount)
        assertEquals(0, snapshot.engine.startingCount)
        assertEquals(0, snapshot.engine.stoppingCount)
        assertEquals(0, snapshot.engine.configMissingCount)
        assertEquals(true, snapshot.wfc?.readable)
        assertEquals(1, snapshot.wfc?.global)
        assertNull(EnvironmentDiagnosticsCodec.failure(HEALTHY_WITH_SECRETS))
    }

    @Test
    fun treatsMissingWfcAsOptional() {
        val snapshot = EnvironmentDiagnosticsCodec.snapshot(healthyJson(wfc = null))

        assertNull(snapshot.wfc)
    }

    @Test
    fun rejectsNonSuccessSnapshot() {
        assertFailsWith<EnvironmentDiagnosticsFormatException> {
            EnvironmentDiagnosticsCodec.snapshot(healthyJson(ok = false))
        }
    }

    @Test
    fun rejectsMissingRequiredStructures() {
        assertFailsWith<EnvironmentDiagnosticsFormatException> {
            EnvironmentDiagnosticsCodec.snapshot("""{"ok":true,"root":{"capable":true}}""")
        }
        assertFailsWith<EnvironmentDiagnosticsFormatException> {
            EnvironmentDiagnosticsCodec.snapshot(healthyJson(includeOk = false))
        }
    }

    @Test
    fun rejectsMalformedJson() {
        assertFailsWith<EnvironmentDiagnosticsFormatException> {
            EnvironmentDiagnosticsCodec.snapshot("""{"module":{"installed":true}""")
        }
    }

    @Test
    fun parsesStructuredCommandFailure() {
        val failure = EnvironmentDiagnosticsCodec.failure(
            """{"ok":false,"code":"USAGE","message":"control.sh list|diagnose"}""",
        )

        assertEquals("USAGE", failure?.code)
        assertEquals("control.sh list|diagnose", failure?.message)
    }
}
