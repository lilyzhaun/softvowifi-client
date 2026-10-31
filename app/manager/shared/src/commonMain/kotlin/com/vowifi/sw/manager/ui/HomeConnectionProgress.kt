package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus

/** Only active connection milestones replace the existing engine lifecycle label. */
internal fun homeConnectionBadge(lifecycle: EngineLifecycle, runtime: EngineRuntimeStatus?): String {
    val engine = engineLabel(lifecycle)
    if (lifecycle != EngineLifecycle.RUNNING) return engine
    return when (runtime?.tunnel) {
        "CONNECTING" -> "隧道连接中"
        "CONNECTED" -> if (runtime.ims == "REGISTERED") engine else "隧道已连接"
        else -> engine
    }
}

/** One current phase, never a timed sequence or the log screen's selected historical tail. */
internal fun homeConnectionSummary(lifecycle: EngineLifecycle, runtime: EngineRuntimeStatus?): String {
    if (lifecycle == EngineLifecycle.STOPPED || lifecycle == EngineLifecycle.CONFIG_MISSING || runtime == null)
        return "隧道 状态未知"
    return when (runtime.tunnel) {
        "CONNECTED" -> "IMS " + when (runtime.ims) {
            "REGISTERED" -> "已注册"
            "REGISTERING" -> when (runtime.imsProgress) {
                "PREPARING_TRANSPORT" -> "Preparing transport"
                "SENDING_REGISTER" -> "Sending REGISTER"
                "AKA_AUTHENTICATION" -> "AKA authentication"
                "INSTALLING_SECURITY" -> "Installing security"
                "SENDING_AUTHENTICATED_REGISTER" -> "Authenticated REGISTER"
                "BUILDING_REGISTER" -> "Building REGISTER"
                "REGISTER_CHALLENGE_RECEIVED" -> "REGISTER challenge received"
                "BUILDING_AUTHORIZATION" -> "Building authorization"
                "REGISTER_ACCEPTED" -> "REGISTER accepted"
                else -> "注册中"
            }
            "", "UNREGISTERED" -> homeNonBreakingPhrase("未注册")
            else -> "状态未知"
        }
        "CONNECTING" -> "隧道 " + when (runtime.tunnelProgress) {
            "RESOLVING_EPDG" -> "Resolving ePDG"
            "IKE_NEGOTIATION" -> "IKE negotiation"
            "EAP_AKA_AUTHENTICATION" -> "EAP-AKA authentication"
            "IKE_SA_INIT_RESPONSE" -> "Processing IKE_SA_INIT response"
            "EAP_CHALLENGE_RECEIVED" -> "EAP challenge received"
            "SIM_AKA_COMPLETE" -> "SIM authentication complete"
            "SENDING_EAP_RESPONSE" -> "Sending EAP response"
            "EAP_SUCCESS_RECEIVED" -> "EAP success received"
            "CHILD_SA_NEGOTIATED" -> "Child SA negotiated"
            "DATAPLANE_READY" -> "IPsec dataplane ready"
            "ROUTING_READY" -> "Routing ready"
            else -> "连接中"
        }
        "DISCONNECTED" -> "隧道 未连接"
        "BACKOFF" -> "隧道 等待重连"
        "FATAL" -> "隧道 连接失败"
        else -> "隧道 状态未知"
    }
}
