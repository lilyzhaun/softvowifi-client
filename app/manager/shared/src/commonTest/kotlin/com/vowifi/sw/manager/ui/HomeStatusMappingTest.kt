package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomeStatusMappingTest {
    @Test fun tunnelStatesUseOnlyBoundedChineseLabelsAndNeverExposeThePid() {
        for ((state, label) in listOf("CONNECTING" to "连接中", "BACKOFF" to "等待重连", "FATAL" to "连接失败",
            "DISCONNECTED" to "未连接", "unexpected" to "状态未知", "connected" to "状态未知",
            " CONNECTED " to "状态未知", "" to "状态未知")) {
            val row = project(runtime(state, "REGISTERED"))
            assertFalse(row.summary.contains("PLMN"), "首页连接摘要不再重复设置页的PLMN")
            assertEquals("隧道 $label", row.summary)
            assertFalse(row.summary.contains("54321"))
            assertFalse(row.summary.contains("进程"))
        }
    }

    @Test fun imsStatesDistinguishRegistrationFromUnknownInsteadOfEchoingRawEnums() {
        for ((state, label) in listOf("REGISTERED" to "已注册", "REGISTERING" to "注册中",
            "UNREGISTERED" to homeNonBreakingPhrase("未注册"), "" to homeNonBreakingPhrase("未注册"),
            "unexpected" to "状态未知", "registered" to "状态未知", " REGISTERED " to "状态未知")) {
            val row = project(runtime("CONNECTED", state))
            assertEquals("IMS $label", row.summary)
        }
        assertEquals("隧道 状态未知", project(null).summary)
    }

    private fun project(runtime: EngineRuntimeStatus?) = homeDashboardPresentation(RegistrationUiState.Empty,
        SimControlUiState.Ready(listOf(SimControlInstance(SimHandle("sim-test"), "SIM 1",
            SimPresence.ACTIVE_MATCHED, 0, "合成运营商", "001", "01", EngineLifecycle.RUNNING,
            pid = 54321, runtime = runtime, canStart = false, canStop = true, canApply = false))),
        EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()), 0).actions.single()

    private fun runtime(tunnel: String, ims: String) = EngineRuntimeStatus(tunnel = tunnel,
        reconnects = 0, ims = ims, call = "IDLE", transport = "UDP", pcscf = emptyList())
}
