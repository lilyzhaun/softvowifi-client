package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomeStatusMappingTest {
    @Test fun tunnelStatesUseOnlyBoundedChineseLabelsAndNeverExposeThePid() {
        for ((state, label) in listOf("CONNECTED" to "已连接", "CONNECTING" to "连接中",
            "DISCONNECTED" to "未连接", "unexpected" to "状态未知", "connected" to "状态未知",
            " CONNECTED " to "状态未知", "" to "状态未知")) {
            val row = project(runtime(state, "REGISTERED"))
            assertEquals("PLMN 001/01\n隧道 $label · IMS 已注册", row.summary)
            assertFalse(row.summary.contains("54321"))
            assertFalse(row.summary.contains("进程"))
        }
    }

    @Test fun imsStatesDistinguishRegistrationFromUnknownInsteadOfEchoingRawEnums() {
        for ((state, label) in listOf("REGISTERED" to "已注册", "REGISTERING" to "注册中",
            "UNREGISTERED" to homeNonBreakingPhrase("未注册"), "" to homeNonBreakingPhrase("未注册"),
            "unexpected" to "状态未知", "registered" to "状态未知", " REGISTERED " to "状态未知")) {
            assertEquals("PLMN 001/01\n隧道 已连接 · IMS $label", project(runtime("CONNECTED", state)).summary)
        }
        assertEquals("PLMN 001/01\n隧道 状态未知 · IMS ${homeNonBreakingPhrase("未注册")}", project(null).summary)
    }

    private fun project(runtime: EngineRuntimeStatus?) = homeDashboardPresentation(RegistrationUiState.Empty,
        SimControlUiState.Ready(listOf(SimControlInstance(SimHandle("sim-test"), "SIM 1",
            SimPresence.ACTIVE_MATCHED, 0, "合成运营商", "001", "01", EngineLifecycle.RUNNING,
            pid = 54321, runtime = runtime, canStart = false, canStop = true, canApply = false))),
        EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()), 0).actions.single()

    private fun runtime(tunnel: String, ims: String) = EngineRuntimeStatus(tunnel = tunnel,
        reconnects = 0, ims = ims, call = "IDLE", transport = "UDP", pcscf = emptyList())
}
