package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*

/** Bounded UI adapter for the engine's existing 17-leaf atomic group; not a policy resolver. */
internal enum class CallSignalingField(val wire: String, val title: String) {
    SessionEnabled("sessionTimer.enabled", "启用会话计时"),
    Expires("sessionTimer.expiresSeconds", "会话期限"),
    Minimum("sessionTimer.minimumSeconds", "最小会话期限"),
    Refresher("sessionTimer.refresher", "刷新方声明"),
    RefreshMethod("sessionTimer.refreshMethod", "刷新方法"),
    SessionRequire("sessionTimer.requirePeer", "要求对端支持计时"),
    ReliableUAC("reliableProvisional.uacEnabled", "出向可靠临时响应"),
    ReliableRequire("reliableProvisional.requirePeer", "要求对端可靠响应"),
    ReliableUAS("reliableProvisional.uasMode", "入向可靠响应模式"),
    PreconditionsUAC("preconditions.uacEnabled", "出向前置条件协商"),
    PreconditionsUAS("preconditions.uasEnabled", "入向前置条件协商"),
    PreconditionsRequire("preconditions.requirePeer", "要求对端支持前置条件"),
    WiFiAllowed("preconditions.wifiAllowed", "允许 Wi-Fi 前置条件"),
    RequestURI("addressing.requestURI", "呼叫目标 URI"),
    PhoneContext("addressing.phoneContext", "本地号码上下文"),
    ContactTransport("addressing.contactTransport", "Contact 传输参数"),
    IncludePANI("includePANI", "附带接入网络信息");

    fun value(group: ConfigurationCallSignaling?): String? = when (this) {
        SessionEnabled -> group?.sessionTimer?.enabled?.toString()
        Expires -> group?.sessionTimer?.expiresSeconds?.toString()
        Minimum -> group?.sessionTimer?.minimumSeconds?.toString()
        Refresher -> group?.sessionTimer?.refresher
        RefreshMethod -> group?.sessionTimer?.refreshMethod
        SessionRequire -> group?.sessionTimer?.requirePeer?.toString()
        ReliableUAC -> group?.reliableProvisional?.uacEnabled?.toString()
        ReliableRequire -> group?.reliableProvisional?.requirePeer?.toString()
        ReliableUAS -> group?.reliableProvisional?.uasMode
        PreconditionsUAC -> group?.preconditions?.uacEnabled?.toString()
        PreconditionsUAS -> group?.preconditions?.uasEnabled?.toString()
        PreconditionsRequire -> group?.preconditions?.requirePeer?.toString()
        WiFiAllowed -> group?.preconditions?.wifiAllowed?.toString()
        RequestURI -> group?.addressing?.requestURI
        PhoneContext -> group?.addressing?.phoneContext
        ContactTransport -> group?.addressing?.contactTransport
        IncludePANI -> group?.includePANI?.toString()
    }

    fun withValue(group: ConfigurationCallSignaling, input: String?): ConfigurationCallSignaling {
        val value = input?.trim()?.takeIf(String::isNotEmpty)
        fun bool(): Boolean? = when (value) {
            null -> null; "true" -> true; "false" -> false
            else -> throw ConfigurationFormatException.InvalidCallSignaling()
        }
        fun seconds(): Int? {
            if (value == null) return null
            return value.toIntOrNull()?.takeIf { it >= 90 } ?: throw ConfigurationFormatException.InvalidCallSignaling()
        }
        return when (this) {
            SessionEnabled -> group.copy(sessionTimer = group.sessionTimer.copy(enabled = bool()))
            Expires -> group.copy(sessionTimer = group.sessionTimer.copy(expiresSeconds = seconds()))
            Minimum -> group.copy(sessionTimer = group.sessionTimer.copy(minimumSeconds = seconds()))
            Refresher -> group.copy(sessionTimer = group.sessionTimer.copy(refresher = value))
            RefreshMethod -> group.copy(sessionTimer = group.sessionTimer.copy(refreshMethod = value))
            SessionRequire -> group.copy(sessionTimer = group.sessionTimer.copy(requirePeer = bool()))
            ReliableUAC -> group.copy(reliableProvisional = group.reliableProvisional.copy(uacEnabled = bool()))
            ReliableRequire -> group.copy(reliableProvisional = group.reliableProvisional.copy(requirePeer = bool()))
            ReliableUAS -> group.copy(reliableProvisional = group.reliableProvisional.copy(uasMode = value))
            PreconditionsUAC -> group.copy(preconditions = group.preconditions.copy(uacEnabled = bool()))
            PreconditionsUAS -> group.copy(preconditions = group.preconditions.copy(uasEnabled = bool()))
            PreconditionsRequire -> group.copy(preconditions = group.preconditions.copy(requirePeer = bool()))
            WiFiAllowed -> group.copy(preconditions = group.preconditions.copy(wifiAllowed = bool()))
            RequestURI -> group.copy(addressing = group.addressing.copy(requestURI = value))
            PhoneContext -> group.copy(addressing = group.addressing.copy(phoneContext = value))
            ContactTransport -> group.copy(addressing = group.addressing.copy(contactTransport = value))
            IncludePANI -> group.copy(includePANI = bool())
        }
    }

    fun choices(): List<ConfigurationChoice>? = when (this) {
        Expires, Minimum, PhoneContext -> null
        Refresher -> INHERIT + listOf(ConfigurationChoice("omit", "不声明"), ConfigurationChoice("uac", "UAC", "声明由 UAC 刷新，不等同固定指向本机"), ConfigurationChoice("uas", "UAS", "声明由 UAS 刷新，不等同固定指向对端"))
        RefreshMethod -> INHERIT + listOf(ConfigurationChoice("invite", "re-INVITE"), ConfigurationChoice("prefer-update", "优先更新", "仅在对端允许时使用 UPDATE，否则 re-INVITE"))
        ReliableUAS -> INHERIT + listOf(ConfigurationChoice("disabled", "关闭"), ConfigurationChoice("sdp-only", "仅 SDP", "可靠传输带 SDP 的临时响应"), ConfigurationChoice("all-18x", "全部 18x", "可靠传输 180/183 临时响应"))
        RequestURI -> INHERIT + listOf(ConfigurationChoice("preserve", "保留输入"), ConfigurationChoice("sip", "SIP 电话 URI"), ConfigurationChoice("tel", "TEL 电话 URI"))
        ContactTransport -> INHERIT + listOf(ConfigurationChoice("omit", "省略"), ConfigurationChoice("selected", "选定协议", "在 Contact URI 中标明当前选定的 TCP 或 UDP"))
        else -> INHERIT + listOf(ConfigurationChoice("true", "开启"), ConfigurationChoice("false", "关闭"))
    }

    fun display(raw: String?): String = when {
        raw == null -> "未知"
        this == RefreshMethod && raw == "update" -> "UPDATE（兼容默认）"
        this == PhoneContext && raw == "home-domain" -> "IMS 归属域"
        this == Expires || this == Minimum -> "$raw 秒"
        else -> choices()?.firstOrNull { it.value == raw }?.label ?: raw
    }

    val description: String get() = when (this) {
        Expires, Minimum -> "单位为秒，范围 90–2147483647；会话期限不能小于最小期限。留空继承，不改生产协议时钟。"
        PhoneContext -> "本地 TEL 号码的作用域，不是拨号前缀。留空继承；可填 home-domain、合法域名或 +数字。呼叫目标已有的上下文优先，不为全局号码强加上下文。"
        else -> ""
    }
}

private val INHERIT = listOf(ConfigurationChoice("", "继承", "使用当前目录或项目默认，不写回解析值"))

internal data class CallSignalingTextEdit(val field: CallSignalingField, val sequence: Long)

internal fun callSignalingFieldSummary(instance: ConfigurationInstance, field: CallSignalingField, modified: Boolean): String {
    val name = "callSignaling.${field.wire}"
    val local = instance.facts?.fields?.firstOrNull { it.field == name }
    val loaded = instance.runtimeFacts?.loaded?.fields?.firstOrNull { it.field == name }
    return "当前解析：${field.display(local?.value)} · ${local?.source?.let(::configurationSourceLabel) ?: "来源未知"}" +
        "\n运行默认（下次呼叫）：${field.display(loaded?.value)}" + if (modified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
}
