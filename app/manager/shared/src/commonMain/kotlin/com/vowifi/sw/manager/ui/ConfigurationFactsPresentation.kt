package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationFact
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransportQualitySample

internal fun configurationApplicationSummary(instance: ConfigurationInstance): String {
    if (instance.lifecycle == ConfigurationLifecycle.STOPPED) return "引擎未运行；已保存配置将在下次启动时读取。"
    val current = instance.facts
    val loaded = instance.runtimeFacts?.loaded
    return when {
        current == null || loaded == null -> "运行配置未知；可刷新核对，旧版引擎可能不提供此信息。"
        // Independent reads establish a difference, not which sample is newer.
        current.configRevision != loaded.configRevision -> "读到的保存版与运行版不同；请刷新核对。"
        current.resolutionRevision != loaded.resolutionRevision -> "解析结果或目录已变化，尚未用于当前连接。"
        else -> "当前引擎已加载这一版配置；不代表注册、短信或通话已成功。"
    }
}

internal fun configurationFactsSummary(instance: ConfigurationInstance, field: String): String {
    val current = instance.facts?.fields?.firstOrNull { it.field == field }
    val loaded = instance.runtimeFacts?.loaded?.fields?.firstOrNull { it.field == field }
    return "当前解析：${configurationFactValue(current)}\n运行加载：${configurationFactValue(loaded)}"
}

internal fun configurationCallFactsSummary(instance: ConfigurationInstance): String {
    val runtime = instance.runtimeFacts
    val call = runtime?.call.takeIf { instance.lifecycle == ConfigurationLifecycle.RUNNING }
        ?: return "当前呼叫配置未知或没有活动呼叫；不会以运行默认补造协商结果。"
    val captured = if (call.configRevision == runtime?.loaded?.configRevision)
        "本次呼叫使用当前运行默认的捕获修订。" else "本次呼叫保留先前捕获的修订；新设置只用于后续呼叫。"
    val session = if (call.sessionSeconds != null)
        "已确认的会话期限：${call.sessionSeconds} 秒；刷新方：${if (call.sessionRefresher == "local") "本机" else "对端"}。"
        else "暂无已确认且仍有效的会话计时协商；不据本地开关推断对端支持。"
    val media = call.media ?: return "$captured\n$session\n实际媒体尚未确认。"
    val codec = media.codec.wire.uppercase()
    val direction = when (media.direction) {
        "sendrecv" -> "双向"
        "sendonly" -> "仅发送"
        "recvonly" -> "仅接收"
        "inactive" -> "不传输"
        else -> "未知方向"
    }
    val protection = media.security?.let { security ->
        security.suite?.let { "SDES · ${mediaSuiteLabel(it)}" } ?: "RTP（无 SRTP）"
    } ?: "未报告"
    return "$captured\n$session\n实际媒体：$codec / ${media.framing.name}，$direction，${media.clockRate} Hz；" +
        "发送 ${media.sendPtimeMs} ms，接收上限 ${media.receiveMaxPtimeMs} ms。\n" +
        "媒体保护：$protection。\n" +
        "最近 RTCP 传输报告（不代表可听）：\n本地接收：${transportQualityText(media.quality.receive)}\n" +
        "对端报告：${transportQualityText(media.quality.send)}"
}

private fun transportQualityText(sample: ConfigurationTransportQualitySample?): String =
    sample?.let { "丢包分数 ${it.fractionLost}/256，抖动 ${it.jitterMs} ms，累计丢包 ${it.totalLost}。" } ?: "未测量。"

internal fun configurationTunnelFactsSummary(instance: ConfigurationInstance): String {
    val facts = instance.runtimeFacts?.tunnel.takeIf { instance.lifecycle == ConfigurationLifecycle.RUNNING }
        ?: return "当前隧道所选算法未知；可在连接后刷新核对。"
    val ike = facts.ike
    val child = facts.child
    val encryption = if (child.encryption == 20) "AES-GCM" else "AES-CBC"
    val integrity = if (child.encryption == 20) "AEAD · 128 位标签" else tunnelIntegrityLabel(child.integrity)
    val prf = when (ike.prf) {
        2 -> "HMAC-SHA1"
        4 -> "AES-XCBC"
        5 -> "HMAC-SHA2-256"
        6 -> "HMAC-SHA2-384"
        7 -> "HMAC-SHA2-512"
        else -> "未知"
    }
    return "IKE：AES-CBC ${ike.keyBits} 位 · ${tunnelIntegrityLabel(ike.integrity)}\n" +
        "PRF $prf · DH${ike.dh}\n" +
        "Child：$encryption ${child.keyBits} 位 · $integrity\n" +
        "${if (child.esn) "ESN · 64 位序列号" else "无 ESN"} · ${if (child.pfs == 14) "重键 PFS · DH14" else "无额外 PFS"}\n当前认证连接的协商快照；不代表内核状态已独立核验。"
}

private fun tunnelIntegrityLabel(id: Int): String = when (id) {
    2 -> "HMAC-SHA1-96"
    12 -> "HMAC-SHA2-256-128"
    13 -> "HMAC-SHA2-384-192"
    14 -> "HMAC-SHA2-512-256"
    else -> "未知"
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
    val displayed = if (fact.field == "epdgAddressFamily") configurationAddressFamilyLabel(value) else value.ifEmpty { "无" }
    return "$displayed（$source）"
}

internal val configurationFactLabels = linkedMapOf(
    "transport" to "底层网络", "epdgFqdn" to "ePDG 地址", "epdgAddressFamily" to "ePDG 地址族", "imsRealm" to "IMS 域",
    "ikeProposalLayout" to "IKE 提议布局", "ikeDHGroup" to "IKE DH 组", "protectedTCPMSS" to "IMS TCP MSS",
    "imsSipSecurity" to "SIP 安全策略", "paniInitial" to "初始 PANI", "paniAuthenticated" to "认证后 PANI",
    "paniCountry" to "PANI 国家", "dtmfNarrowband" to "窄带 DTMF PT", "dtmfWideband" to "宽带 DTMF PT",
    "dns" to "DNS", "smsc" to "短信中心", "imsDigestRealm" to "Digest 认证域",
)
