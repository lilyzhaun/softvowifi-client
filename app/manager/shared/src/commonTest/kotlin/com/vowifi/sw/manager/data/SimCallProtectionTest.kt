package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class SimCallProtectionTest {
    @Test fun runtimeCallStagesProtectOnlyThatInstanceWithoutAnAppBinding() {
        val a = SimHandle("sim-1"); val b = SimHandle("sim-2")
        for (stage in listOf("DIALING", "RINGING", "IN_CALL")) {
            val state = SimControlUiState.Ready(listOf(sim(a, 0, stage), sim(b, 1, "IDLE")))
            assertEquals(mapOf(a to 0), activeCallSimsFor(state, CallUiState.Unavailable("未连接")))
        }
        assertTrue(activeCallSimsFor(SimControlUiState.Ready(listOf(sim(a, 0, "UNKNOWN")))).isEmpty())
    }

    @Test fun appCallMatchesTheRealOpaqueHandleStoreAndNeverAnotherLineOrIdleTerminal() {
        val handles = OpaqueIccidHandleStore()
        // Deliberately allocate handles out of slot order: sim-N is not a physical SIM number.
        val b = handles.handleFor("synthetic-transport-b"); val a = handles.handleFor("synthetic-transport-a")
        val state = SimControlUiState.Ready(listOf(sim(a, 0, "IDLE"), sim(b, 1, "IDLE")))
        for (phase in CallPhase.entries) {
            val call = CallUiState.Ready(phase, CallAudioState.Idle, CallOrigin.ENGINE,
                binding = CallBinding("synthetic-transport-b", "synthetic-call", 1))
            assertEquals(if (phase == CallPhase.Idle) emptyMap() else mapOf(b to 1),
                activeCallSimsFor(state, call, handles::keyFor), "App phase=$phase")
        }
        val unmapped = CallUiState.Ready(CallPhase.InCall, CallAudioState.Idle, CallOrigin.ENGINE,
            binding = CallBinding("synthetic-transport-other", "synthetic-call", 1))
        assertTrue(activeCallSimsFor(state, unmapped, handles::keyFor).isEmpty())
        assertTrue(activeCallSimsFor(SimControlUiState.Failure("读取失败"), unmapped, handles::keyFor).isEmpty())
    }

    @Test fun runtimeAndAppGuardsCombineAndPublicWarningDoesNotContainTransportKeys() {
        val handles = OpaqueIccidHandleStore()
        val a = handles.handleFor("synthetic-transport-a"); val b = handles.handleFor("synthetic-transport-b")
        val state = SimControlUiState.Ready(listOf(sim(a, 0, "RINGING"), sim(b, 1, "IDLE")))
        val call = CallUiState.Ready(CallPhase.InCall, CallAudioState.Idle, CallOrigin.ENGINE,
            binding = CallBinding("synthetic-transport-b", "synthetic-call", 1))
        assertEquals(mapOf(a to 0, b to 1), activeCallSimsFor(state, call, handles::keyFor))
        assertEquals("SIM 1 正在通话，停止引擎会挂断当前通话。", com.vowifi.sw.manager.ui.engineStopCallWarning(0))
        assertEquals("该线路正在通话，停止引擎会挂断当前通话。", com.vowifi.sw.manager.ui.engineStopCallWarning(null))
        assertFalse(activeCallSimsFor(state, call, handles::keyFor).keys.any { "transport" in it.value })
    }

    private fun sim(handle: SimHandle, slot: Int, call: String) = SimControlInstance(handle, "测试线路", SimPresence.ACTIVE_MATCHED,
        slot, null, null, null, EngineLifecycle.RUNNING,
        runtime = EngineRuntimeStatus("CONNECTED", reconnects = 0, ims = "REGISTERED", call = call, transport = "WIFI", pcscf = emptyList()),
        canStart = false, canStop = true, canApply = true)
}
