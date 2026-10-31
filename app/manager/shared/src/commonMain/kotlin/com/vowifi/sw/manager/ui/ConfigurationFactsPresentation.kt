package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationFact
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationLifecycle

internal fun configurationApplicationSummary(instance: ConfigurationInstance): String {
    if (instance.lifecycle == ConfigurationLifecycle.STOPPED) return "引擎未运行；已保存配置将在下次启动时读取。"
    val current = instance.facts
    val loaded = instance.runtimeFacts?.loaded
    return when {
        current == null || loaded == null -> "运行配置未知；可刷新核对，旧版引擎可能不提供此信息。"
        current.configRevision != loaded.configRevision -> "已保存，尚未用于当前连接。"
        current.resolutionRevision != loaded.resolutionRevision -> "解析结果或目录已变化，尚未用于当前连接。"
        else -> "当前引擎已加载这一版配置；不代表注册、短信或通话已成功。"
    }
}

internal fun configurationFactsSummary(instance: ConfigurationInstance, field: String): String {
    val current = instance.facts?.fields?.firstOrNull { it.field == field }
    val loaded = instance.runtimeFacts?.loaded?.fields?.firstOrNull { it.field == field }
    return "当前解析：${configurationFactValue(current)}\n运行加载：${configurationFactValue(loaded)}"
}

private fun configurationFactValue(fact: ConfigurationFact?): String {
    val value = fact?.value ?: return "未知"
    val source = when (fact.source) {
        "user" -> "手动设置"
        "saved" -> "已保存选择"
        "catalog", "carrier" -> "运营商目录"
        "default" -> "默认值"
        "formula" -> "标准推导"
        "home_mcc" -> "归属国家"
        "sim" -> "SIM 读取"
        "network" -> "当前网络"
        "identity" -> "身份域"
        "diagnostic" -> "临时诊断"
        else -> "来源未知"
    }
    return "${value.ifEmpty { "无" }}（$source）"
}

internal val configurationFactLabels = linkedMapOf(
    "transport" to "底层网络", "epdgFqdn" to "ePDG 地址", "imsRealm" to "IMS 域",
    "ikeProposalLayout" to "IKE 提议布局", "ikeDHGroup" to "IKE DH 组", "protectedTCPMSS" to "IMS TCP MSS",
    "imsSipSecurity" to "SIP 安全策略", "paniInitial" to "初始 PANI", "paniAuthenticated" to "认证后 PANI",
    "paniCountry" to "PANI 国家", "dtmfNarrowband" to "窄带 DTMF PT", "dtmfWideband" to "宽带 DTMF PT",
    "dns" to "DNS", "smsc" to "短信中心", "imsDigestRealm" to "Digest 认证域",
)
