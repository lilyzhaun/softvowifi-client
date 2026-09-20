package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineControlBandTest {
    @Test
    fun usesSuccessToneOnlyWhenTunnelIsConnected() {
        val running = instance(
            handle = SimHandle("running"),
            displayLabel = "SIM 1",
            presence = SimPresence.ACTIVE_MATCHED,
            lifecycle = EngineLifecycle.RUNNING,
            runtime = runtime("CONNECTING"),
        )
        val connected = running.copy(runtime = runtime("CONNECTED"))

        assertEquals(EngineTone.NEUTRAL, engineTone(running))
        assertEquals(EngineTone.SUCCESS, engineTone(connected))
    }

    @Test
    fun errorMessageOverridesLifecycleTone() {
        val failed = instance(
            handle = SimHandle("failed"),
            displayLabel = "SIM 1",
            presence = SimPresence.ACTIVE_MATCHED,
            lifecycle = EngineLifecycle.RUNNING,
            runtime = runtime("CONNECTED"),
            message = "socket denied",
        )

        assertEquals(EngineTone.ERROR, engineTone(failed))
    }

    @Test
    fun presentsMatchedRowsInReadyOrderWithTypedActions() {
        val stoppedHandle = SimHandle("sim-a")
        val runningHandle = SimHandle("sim-b")
        val presentation = simControlBandPresentation(
            SimControlUiState.Ready(
                listOf(
                    instance(stoppedHandle, "SIM 1 · 本地运营商", SimPresence.ACTIVE_MATCHED, EngineLifecycle.STOPPED, canStart = true),
                    instance(runningHandle, "SIM 2 · 漫游运营商", SimPresence.ACTIVE_MATCHED, EngineLifecycle.RUNNING, canStop = true),
                ),
            ),
        )

        assertEquals(listOf("SIM 1 · 本地运营商", "SIM 2 · 漫游运营商"), presentation.rows.map { it.title })
        assertEquals(EngineActionId.START, presentation.rows[0].action.id)
        assertEquals(stoppedHandle, presentation.rows[0].action.handle)
        assertTrue(presentation.rows[0].action.enabled)
        assertEquals(EngineActionId.STOP, presentation.rows[1].action.id)
        assertEquals(runningHandle, presentation.rows[1].action.handle)
        assertTrue(presentation.rows[1].action.enabled)
    }

    @Test
    fun presentsBusyUnconfiguredAndRemovedCapabilities() {
        val starting = instance(
            SimHandle("starting"),
            "SIM 1",
            SimPresence.ACTIVE_MATCHED,
            EngineLifecycle.STARTING,
        )
        val unconfigured = instance(
            SimHandle("unconfigured"),
            "SIM 2",
            SimPresence.ACTIVE_UNCONFIGURED,
            EngineLifecycle.CONFIG_MISSING,
            message = "460001234567890 /data/private/status",
        )
        val stopping = instance(
            SimHandle("stopping"),
            "SIM 3",
            SimPresence.ACTIVE_MATCHED,
            EngineLifecycle.STOPPING,
        )
        val removedHandle = SimHandle("removed")
        val removed = instance(
            removedHandle,
            "实例 •••• 4321",
            SimPresence.REMOVED_CONFIG_RETAINED,
            EngineLifecycle.RUNNING,
            canStop = true,
            message = "460001234567890",
        )
        val removedStopped = instance(
            SimHandle("removed-stopped"),
            "实例 •••• 8765",
            SimPresence.REMOVED_CONFIG_RETAINED,
            EngineLifecycle.STOPPED,
        )

        val rows = simControlBandPresentation(
            SimControlUiState.Ready(listOf(starting, stopping, unconfigured, removed, removedStopped)),
        ).rows

        assertEquals(EngineActionId.START, rows[0].action.id)
        assertFalse(rows[0].action.enabled)
        assertEquals(EngineActionId.STOP, rows[1].action.id)
        assertFalse(rows[1].action.enabled)
        assertEquals("未配置/未支持", rows[2].statusText)
        assertEquals("未配置/未支持", rows[2].message)
        assertEquals(EngineActionId.START, rows[2].action.id)
        assertFalse(rows[2].action.enabled)
        assertEquals("SIM 已移除/配置保留", rows[3].statusText)
        assertEquals("SIM 已移除/配置保留", rows[3].message)
        assertEquals(EngineActionId.STOP, rows[3].action.id)
        assertEquals(removedHandle, rows[3].action.handle)
        assertTrue(rows[3].action.enabled)
        assertEquals(EngineActionId.START, rows[4].action.id)
        assertFalse(rows[4].action.enabled)
        assertTrue(rows.flatMap { it.visibleStrings }.none { "/data/" in it || Regex("\\d{15,}").containsMatchIn(it) })
    }

    @Test
    fun dispatchesOnlyTypedHandles() {
        val handle = SimHandle("opaque-handle")
        val starts = mutableListOf<SimHandle>()
        val stops = mutableListOf<SimHandle>()

        dispatchEngineAction(EngineActionPresentation(EngineActionId.START, handle, "启动引擎", true), starts::add, stops::add)
        dispatchEngineAction(EngineActionPresentation(EngineActionId.STOP, handle, "停止引擎", true), starts::add, stops::add)

        assertEquals(listOf(handle), starts)
        assertEquals(listOf(handle), stops)
    }

    @Test
    fun boundsTopLevelMessagesAndEmptyCopy() {
        val empty = simControlBandPresentation(SimControlUiState.Ready(emptyList()))
        val safeReady = simControlBandPresentation(
            SimControlUiState.Ready(emptyList(), message = "引擎暂时不可用"),
        )
        val pathReady = simControlBandPresentation(
            SimControlUiState.Ready(emptyList(), message = "/data/adb/vowifi/config/x.json"),
        )
        val digitsReady = simControlBandPresentation(
            SimControlUiState.Ready(emptyList(), message = "460001234567890"),
        )
        val permission = simControlBandPresentation(SimControlUiState.PermissionDenied("/data/private/status"))
        val failure = simControlBandPresentation(SimControlUiState.Failure("460001234567890"))

        assertEquals("当前没有可显示的 SIM", empty.message)
        assertEquals("引擎暂时不可用", safeReady.message)
        assertEquals(EngineTone.WARNING, safeReady.messageTone)
        assertEquals("SIM 控制状态可能不完整", pathReady.message)
        assertEquals("SIM 控制状态可能不完整", digitsReady.message)
        assertEquals("当前权限无法读取 SIM 控制状态", permission.message)
        assertEquals(EngineTone.WARNING, permission.messageTone)
        assertEquals("无法读取 SIM 控制状态", failure.message)
        assertEquals(EngineTone.ERROR, failure.messageTone)
        assertTrue(
            listOfNotNull(empty.message, safeReady.message, pathReady.message, digitsReady.message, permission.message, failure.message)
                .none { "/data/" in it || Regex("\\d{15,}").containsMatchIn(it) },
        )
    }

    private fun runtime(tunnel: String) = EngineRuntimeStatus(
        tunnel = tunnel,
        reconnects = 0,
        ims = "UNREGISTERED",
        call = "IDLE",
        transport = "UDP",
        pcscf = emptyList(),
    )

    private fun instance(
        handle: SimHandle,
        displayLabel: String,
        presence: SimPresence,
        lifecycle: EngineLifecycle,
        canStart: Boolean = false,
        canStop: Boolean = false,
        runtime: EngineRuntimeStatus? = null,
        message: String? = null,
    ) = SimControlInstance(
        handle = handle,
        displayLabel = displayLabel,
        presence = presence,
        slotIndex = null,
        carrierLabel = null,
        mcc = null,
        mnc = null,
        lifecycle = lifecycle,
        runtime = runtime,
        message = message,
        canStart = canStart,
        canStop = canStop,
        canApply = false,
        canProvision = false,
    )
}
