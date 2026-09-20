package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineControlScriptCodecTest {
    @Test
    fun parsesConfiguredInstances() {
        val instances = EngineControlScriptCodec.instances(
            """[{"ok":true,"iccid":"8986000000000000001","state":"running","pid":4242},{"ok":true,"iccid":"8986000000000000002","state":"stopped","pid":null}]""",
        )

        assertEquals(2, instances.size)
        assertEquals(EngineLifecycle.RUNNING, instances[0].lifecycle)
        assertEquals(4242L, instances[0].pid)
        assertEquals(EngineLifecycle.STOPPED, instances[1].lifecycle)
    }

    @Test
    fun rejectsUnknownLifecycle() {
        assertFailsWith<IllegalArgumentException> {
            EngineControlScriptCodec.instances(
                """[{"ok":true,"iccid":"8986000000000000001","state":"future","pid":null}]""",
            )
        }
    }

    @Test
    fun parsesEveryScriptLifecycle() {
        val instances = EngineControlScriptCodec.instances(
            """[{"ok":true,"iccid":"1","state":"config_missing"},{"ok":true,"iccid":"2","state":"starting"},{"ok":true,"iccid":"3","state":"stopping"}]""",
        )

        assertEquals(
            listOf(EngineLifecycle.CONFIG_MISSING, EngineLifecycle.STARTING, EngineLifecycle.STOPPING),
            instances.map { it.lifecycle },
        )
    }

    @Test
    fun parsesStructuredFailure() {
        val failure = EngineControlScriptCodec.failure(
            """{"ok":false,"code":"CONFIG_MISSING","message":"/data/adb/vowifi/config/8986000000000000001.json"}""",
        )

        assertEquals("CONFIG_MISSING", failure?.code)
        assertEquals("/data/adb/vowifi/config/8986000000000000001.json", failure?.message)
    }

    @Test
    fun preservesLifecycleWhenAnotherOperationOrCleanupIsStillActive() {
        assertEquals(
            EngineLifecycle.STARTING,
            lifecycleAfterControlFailure(EngineLifecycle.STARTING, "BUSY"),
        )
        assertEquals(
            EngineLifecycle.STARTING,
            lifecycleAfterControlFailure(EngineLifecycle.STARTING, "START_CLEANUP_FAILED"),
        )
        assertEquals(
            EngineLifecycle.CONFIG_MISSING,
            lifecycleAfterControlFailure(EngineLifecycle.STARTING, "CONFIG_MISSING"),
        )
        assertEquals(
            EngineLifecycle.STOPPED,
            lifecycleAfterControlFailure(EngineLifecycle.STARTING, "START_TIMEOUT"),
        )
    }

    @Test
    fun mapsIdentityMismatchToStoppedAndFixedChineseMessage() {
        assertEquals(
            EngineLifecycle.STOPPED,
            lifecycleAfterControlFailure(EngineLifecycle.STARTING, "IDENTITY_MISMATCH"),
        )
        assertEquals(
            "SIM 与配置身份不一致，已阻止启动",
            controlFailureMessage("IDENTITY_MISMATCH", "SIM identity does not match config"),
        )
    }

    @Test
    fun mapsIdentityUnreadableToStoppedAndFixedChineseMessage() {
        assertEquals(
            EngineLifecycle.STOPPED,
            lifecycleAfterControlFailure(EngineLifecycle.RUNNING, "IDENTITY_UNREADABLE"),
        )
        assertEquals(
            "无法读取 SIM 身份，已阻止启动",
            controlFailureMessage("IDENTITY_UNREADABLE", "SIM identity could not be read"),
        )
    }

    @Test
    fun keepsUnrelatedControlFailureMessagesUnchanged() {
        assertEquals(
            "/data/adb/vowifi/config/8986000000000000001.json",
            controlFailureMessage("CONFIG_MISSING", "/data/adb/vowifi/config/8986000000000000001.json"),
        )
        assertEquals(
            "engine socket was not ready within 10 seconds",
            controlFailureMessage("START_TIMEOUT", "engine socket was not ready within 10 seconds"),
        )
    }
}
