package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.EngineProtocolCodec
import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

class HomeConnectionProgressTest {
    @Test fun actualStatusFieldsSelectOneCurrentTunnelOrImsStep() {
        val tunnelSteps = mapOf("RESOLVING_EPDG" to "Resolving ePDG", "IKE_NEGOTIATION" to "IKE negotiation",
            "EAP_AKA_AUTHENTICATION" to "EAP-AKA authentication")
        for ((step, text) in tunnelSteps) {
            assertEquals("隧道 $text", project(runtime("CONNECTING", "REGISTERED", step, "SENDING_REGISTER")))
        }
        val imsSteps = mapOf("PREPARING_TRANSPORT" to "Preparing transport", "SENDING_REGISTER" to "Sending REGISTER",
            "AKA_AUTHENTICATION" to "AKA authentication", "INSTALLING_SECURITY" to "Installing security",
            "SENDING_AUTHENTICATED_REGISTER" to "Authenticated REGISTER", "BUILDING_REGISTER" to "Building REGISTER",
            "REGISTER_CHALLENGE_RECEIVED" to "REGISTER challenge received", "BUILDING_AUTHORIZATION" to "Building authorization",
            "REGISTER_ACCEPTED" to "REGISTER accepted")
        for ((step, text) in imsSteps) {
            assertEquals("IMS $text", project(runtime("CONNECTED", "REGISTERING", "EAP_AKA_AUTHENTICATION", step)))
        }
        assertEquals("IMS 已注册", project(runtime("CONNECTED", "REGISTERED", "RESOLVING_EPDG", "AKA_AUTHENTICATION")))
    }

    @Test fun stoppedMissingOldAndUnknownSnapshotsNeverInventProgressOrKeepOldImsSuccess() {
        assertEquals("隧道 状态未知", project(null))
        assertEquals("隧道 状态未知", project(runtime("CONNECTED", "REGISTERED", "", ""), EngineLifecycle.STOPPED))
        assertEquals("隧道 连接中", project(runtime("CONNECTING", "REGISTERED", "", "")))
        assertEquals("IMS 注册中", project(runtime("CONNECTED", "REGISTERING", "", "")))
        assertEquals("隧道 连接中", project(runtime("CONNECTING", "REGISTERING", "private-progress", "AKA_AUTHENTICATION")))
        assertEquals("IMS 注册中", project(runtime("CONNECTED", "REGISTERING", "", "/data/private")))
        assertEquals("隧道 等待重连", project(runtime("BACKOFF", "REGISTERED", "EAP_AKA_AUTHENTICATION", "SENDING_REGISTER")))
        assertEquals("隧道 连接失败", project(runtime("FATAL", "REGISTERED", "RESOLVING_EPDG", "SENDING_REGISTER")))
    }

    @Test fun oldWireFieldsAreOptionalAndUntrustedDetailsDoNotEnterTheRuntimeModel() {
        val old = EngineProtocolCodec.status(EngineProtocolFrame.Response(1, buildJsonObject {
            put("tunnel", "CONNECTED"); put("ims", "REGISTERING"); put("reconnects", 0)
            put("call", "IDLE"); put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
        }, null))
        assertEquals("IMS 注册中", project(old))
        val unknown = runtime("CONNECTED", "REGISTERING", "/data/private", "private-progress")
        assertFalse(unknown.toString().contains("private"), "白名单边界不能把未知字段带进SIM模型/日志")
        assertEquals("IMS 注册中", project(unknown))
    }

    @Test fun actualHandshakeNodesRemainDistinctInsteadOfCollapsingIntoOneAuthenticationLabel() {
        for ((step, label) in mapOf("IKE_SA_INIT_RESPONSE" to "Processing IKE_SA_INIT response",
            "EAP_CHALLENGE_RECEIVED" to "EAP challenge received", "SIM_AKA_COMPLETE" to "SIM authentication complete",
            "SENDING_EAP_RESPONSE" to "Sending EAP response", "EAP_SUCCESS_RECEIVED" to "EAP success received",
            "CHILD_SA_NEGOTIATED" to "Child SA negotiated", "DATAPLANE_READY" to "IPsec dataplane ready",
            "ROUTING_READY" to "Routing ready")) {
            assertEquals("隧道 $label", project(runtime("CONNECTING", "UNREGISTERED", step, "")))
        }
    }

    @Test fun badgeShowsTunnelMilestonesAndReturnsToOriginalRunningAfterRegistration() {
        val connecting = row(runtime("CONNECTING", "REGISTERED", "EAP_AKA_AUTHENTICATION", ""))
        assertEquals("隧道连接中", connecting.statusText)
        assertEquals("隧道 EAP-AKA authentication", connecting.summary)
        val waiting = row(runtime("CONNECTED", "UNREGISTERED", "", ""))
        assertEquals("隧道已连接", waiting.statusText)
        val registering = row(runtime("CONNECTED", "REGISTERING", "", "SENDING_REGISTER"))
        assertEquals("隧道已连接", registering.statusText)
        assertEquals("IMS Sending REGISTER", registering.summary)
        val registered = row(runtime("CONNECTED", "REGISTERED", "", ""))
        assertEquals("运行中", registered.statusText)
        assertEquals(SemanticTone.SUCCESS, registered.tone)
        assertEquals("IMS 已注册", registered.summary)
        assertFalse(registered.statusText.contains("IMS"), "最终气泡不能重复右侧IMS已注册")
        assertEquals("隧道已连接", row(runtime("CONNECTED", "unexpected", "", "")).statusText)
    }

    @Test fun milestoneLabelsNeverReplaceExistingLifecycleStatesOrInventUnknownProgress() {
        val staleSuccess = runtime("CONNECTED", "REGISTERED", "", "")
        for ((lifecycle, label) in listOf(EngineLifecycle.STOPPED to "已停止", EngineLifecycle.STARTING to "启动中",
            EngineLifecycle.STOPPING to "停止中", EngineLifecycle.CONFIG_MISSING to "缺少配置")) {
            assertEquals(label, row(staleSuccess, lifecycle).statusText)
        }
        for (tunnel in listOf("DISCONNECTED", "BACKOFF", "FATAL", "unexpected", "connected", " CONNECTED ")) {
            assertEquals("运行中", row(runtime(tunnel, "REGISTERED", "", "")).statusText)
        }
        assertEquals("运行中", row(null).statusText)
    }

    private fun project(runtime: EngineRuntimeStatus?, lifecycle: EngineLifecycle = EngineLifecycle.RUNNING): String =
        row(runtime, lifecycle).summary

    private fun row(runtime: EngineRuntimeStatus?, lifecycle: EngineLifecycle = EngineLifecycle.RUNNING) =
        homeDashboardPresentation(RegistrationUiState.Empty, SimControlUiState.Ready(listOf(SimControlInstance(
            SimHandle("sim-test"), "SIM 1", SimPresence.ACTIVE_MATCHED, 0, "合成运营商", "001", "01", lifecycle,
            runtime = runtime, canStart = lifecycle == EngineLifecycle.STOPPED, canStop = lifecycle == EngineLifecycle.RUNNING,
            canApply = false))), EnvironmentDiagnosticsUiState.Loading, 0).actions.single()

    private fun runtime(tunnel: String, ims: String, tunnelStep: String, imsStep: String) = EngineProtocolCodec.status(
        EngineProtocolFrame.Response(1, buildJsonObject {
            put("tunnel", tunnel); put("ims", ims); put("reconnects", 0); put("call", "IDLE")
            put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
            put("tunnelProgress", tunnelStep); put("imsProgress", imsStep)
        }, null))
}
