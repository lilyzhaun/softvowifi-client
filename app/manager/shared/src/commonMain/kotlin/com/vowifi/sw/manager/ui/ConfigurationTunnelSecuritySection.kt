package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal fun LazyListScope.configurationTunnelSecuritySections(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    item(key = "configuration-tunnel-title") { ConfigurationSectionTitle("隧道安全报价") }
    item(key = "configuration-tunnel-policy") { ConfigurationTunnelPolicyNotice(instance, draft, enabled) }
    if (instance.tunnelSecurity == null) return
    val policy = draft.tunnelSecurity?.policy ?: return
    item(key = "configuration-tunnel-layout") {
        ConfigurationSectionCard {
            val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
            ConfigurationChoicePreference("IKE 报价布局", listOf(
                ConfigurationChoice("multiple-complete", "完整多报价", "每项保留完整算法、长度、PRF 与 DH"),
                ConfigurationChoice("single-combined", "合并单报价", "仅接受不会引入额外组合的完整集合")),
                policy.layout.wire, "布局不改变允许的完整套件集合。", draft.tunnelModified, actionable) { value ->
                if (actionable && draft.tunnelSecurity?.policy == policy) draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(
                    policy.copy(layout = ConfigurationTunnelLayout.entries.first { it.wire == value })))
            }
        }
    }
    item(key = "configuration-tunnel-child-pfs") {
        ConfigurationSectionCard {
            val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
            ConfigurationChoicePreference("Child 重键 PFS", listOf(
                ConfigurationChoice("0", "无额外 PFS", "重键不增加独立 DH 交换"),
                ConfigurationChoice("14", "MODP 2048 · DH14", "重键使用本次新生成的 DH 密钥")),
                policy.childPFSGroup.toString(), "仅用于 Child 重键；初始 IKE_AUTH 不增加独立 KE。下次启动生效。",
                draft.tunnelModified, actionable) { value ->
                if (actionable && draft.tunnelSecurity?.policy == policy)
                    draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(policy.copy(childPFSGroup = value.toInt())))
            }
        }
    }
    policy.ikeSuites.forEachIndexed { index, suite ->
        item(key = "configuration-tunnel-ike-$index") {
            ConfigurationSectionCard {
                val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
                BasicComponent(title = "IKE 套件 ${index + 1}", summary = ikeSuiteDescription(suite))
                ConfigurationChoicePreference("IKE 优先顺序 · ${index + 1}", orderChoices(policy.ikeSuites.size),
                    (index + 1).toString(), "完整套件一起移动。", draft.tunnelModified, actionable) { value ->
                    if (actionable && draft.tunnelSecurity?.policy == policy) {
                        val moved = policy.ikeSuites.toMutableList()
                        moved.add(value.toInt() - 1, moved.removeAt(index))
                        draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(policy.copy(ikeSuites = moved)))
                    }
                }
                ManagerTextAction("移除 IKE 套件 ${index + 1}", ManagerAction.ContextAction, {
                    if (actionable && draft.tunnelSecurity?.policy == policy)
                        draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(policy.copy(ikeSuites = policy.ikeSuites - suite)))
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
            }
        }
    }
    item(key = "configuration-tunnel-ike-add") {
        ConfigurationSectionCard {
            val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
            val options = ConfigurationIkeSuite.entries.filter { it !in policy.ikeSuites &&
                (policy.ikeSuites.isEmpty() || it.dhGroup == policy.ikeSuites.first().dhGroup) }
            if (options.isNotEmpty()) ConfigurationChoicePreference("添加 IKE 套件", listOf(ConfigurationChoice("", "选择套件")) +
                options.map { ConfigurationChoice(it.wire, ikeSuiteLabel(it), ikeSuiteDescription(it)) }, "",
                "同组 DH 必须一致；要切换 DH，请先移除本组 IKE 套件。", draft.tunnelModified, actionable) { value ->
                if (actionable && value.isNotEmpty() && draft.tunnelSecurity?.policy == policy)
                    draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(policy.copy(ikeSuites = policy.ikeSuites + options.first { it.wire == value })))
            } else BasicComponent(title = "IKE 套件已全部选择", summary = "可调整顺序或移除；不会重复报价。")
        }
    }
    policy.childSuites.forEachIndexed { index, suite ->
        item(key = "configuration-tunnel-child-$index") {
            ConfigurationSectionCard {
                val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
                BasicComponent(title = "Child 套件 ${index + 1}", summary = childSuiteDescription(suite))
                ConfigurationChoicePreference("Child 优先顺序 · ${index + 1}", orderChoices(policy.childSuites.size),
                    (index + 1).toString(), "完整套件一起移动。", draft.tunnelModified, actionable) { value ->
                    if (actionable && draft.tunnelSecurity?.policy == policy) {
                        val moved = policy.childSuites.toMutableList()
                        moved.add(value.toInt() - 1, moved.removeAt(index))
                        draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(policy.copy(childSuites = moved)))
                    }
                }
                ManagerTextAction("移除 Child 套件 ${index + 1}", ManagerAction.ContextAction, {
                    if (actionable && draft.tunnelSecurity?.policy == policy)
                        draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(policy.copy(childSuites = policy.childSuites - suite)))
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
            }
        }
    }
    item(key = "configuration-tunnel-child-add") {
        ConfigurationSectionCard {
            val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
            val options = ConfigurationChildSuite.entries.filter { it !in policy.childSuites }
            if (options.isNotEmpty()) ConfigurationChoicePreference("添加 Child 套件", listOf(ConfigurationChoice("", "选择套件")) +
                options.map { ConfigurationChoice(it.wire, childSuiteLabel(it), childSuiteDescription(it)) }, "",
                "ESN 随完整套件选择；额外 DH 由 Child 重键 PFS 单独选择。", draft.tunnelModified, actionable) { value ->
                if (actionable && value.isNotEmpty() && draft.tunnelSecurity?.policy == policy)
                    draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(policy.copy(childSuites = policy.childSuites + options.first { it.wire == value })))
            } else BasicComponent(title = "Child 套件已全部选择", summary = "可调整顺序或移除；不会重复报价。")
        }
    }
}

@Composable
private fun ConfigurationTunnelPolicyNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = if (instance.tunnelSecurity == null) "隧道报价尚未完整确认" else "下次启动生效", summary = when {
            instance.tunnelSecurity == null && draft.tunnelSecurity == null -> "请刷新核对配套引擎；也可明确清除整组后保存，不补猜旧值。"
            instance.tunnelSecurity == null -> "已选择清除整组。请先保存并读回确认，再新建报价；其它草稿保留。"
            draft.tunnelSecurity?.policy == null -> "未设置手动报价；有原 DH、布局覆盖时沿用原策略，否则继承目录，无目录组时使用兼容报价。新建后须自行选择完整 IKE 与 Child 套件。"
            else -> "整组保存，序号小的优先；运行时保存会重启本实例。报价不是网侧已选算法。" + if (draft.tunnelModified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
        })
        instance.facts?.tunnelSecurity?.let { BasicComponent(title = "当前解析的隧道报价", summary = tunnelFactsSummary(it)) }
        BasicComponent(title = "已加载的隧道报价", summary = instance.runtimeFacts?.loaded?.tunnelSecurity?.let(::tunnelFactsSummary)
            ?: "未知；不使用磁盘配置推断运行快照")
        draft.tunnelError?.let { Text(it, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(it)) }
        if (instance.tunnelSecurity != null && draft.tunnelSecurity == ConfigurationTunnelSecurity())
            ManagerTextAction("新建隧道报价", ManagerAction.ContextAction, {
                if (actionable && draft.tunnelSecurity == ConfigurationTunnelSecurity())
                    draft.replaceTunnelSecurity(ConfigurationTunnelSecurity(ConfigurationTunnelPolicy()))
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        if (draft.tunnelSecurity != ConfigurationTunnelSecurity())
            ManagerTextAction("清除隧道报价", ManagerAction.ContextAction, { if (actionable) clearing = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组隧道报价？",
        summary = "清除手动 IKE、Child 套件（含 ESN）、布局及重键 PFS。保留原 DH、布局覆盖；没有这些覆盖时恢复目录继承，无目录组时使用兼容报价。此处只修改草稿，仍需保存；运行时保存会重启本实例。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceTunnelSecurity(ConfigurationTunnelSecurity())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

private fun orderChoices(size: Int) = (1..size).map { ConfigurationChoice(it.toString(), "第 $it 位") }
private fun ikeSuiteLabel(suite: ConfigurationIkeSuite) = "CBC${suite.keyBits} / ${when (suite.integrity) {
    "HMAC-SHA2-256-128" -> "SHA256"
    "HMAC-SHA2-384-192" -> "SHA384"
    else -> "SHA1"
}} / DH${suite.dhGroup}"
private fun ikeSuiteDescription(suite: ConfigurationIkeSuite) = "AES-CBC ${suite.keyBits} 位 · ${suite.integrity}\nPRF ${suite.prf} · DH${suite.dhGroup}"
private fun childSuiteLabel(suite: ConfigurationChildSuite) = when (suite) {
    ConfigurationChildSuite.GCM256, ConfigurationChildSuite.GCM256_ESN -> "GCM256 / AEAD"
    ConfigurationChildSuite.GCM128, ConfigurationChildSuite.GCM128_ESN -> "GCM128 / AEAD"
    ConfigurationChildSuite.CBC256_SHA256, ConfigurationChildSuite.CBC256_SHA256_ESN -> "CBC256 / SHA256"
    ConfigurationChildSuite.CBC128_SHA256, ConfigurationChildSuite.CBC128_SHA256_ESN -> "CBC128 / SHA256"
    ConfigurationChildSuite.CBC128_SHA1, ConfigurationChildSuite.CBC128_SHA1_ESN -> "CBC128 / SHA1"
    ConfigurationChildSuite.CBC256_SHA512, ConfigurationChildSuite.CBC256_SHA512_ESN -> "CBC256 / SHA512"
}.let { if (suite.esn) "$it / ESN" else it }
private fun childSuiteDescription(suite: ConfigurationChildSuite) = "${suite.encryption} ${suite.keyBits} 位 · ${suite.integrity}\n${if (suite.esn) "ESN · 64 位序列号" else "无 ESN"}；重键 PFS 由完整策略组选择"
internal fun tunnelFactsSummary(facts: ConfigurationTunnelSecurityFacts): String = when {
    !facts.known -> "未知；请刷新核对"
    facts.compatibilityBaseline -> "原 DH、布局策略与兼容报价；不是协商结果"
    else -> {
        val policy = facts.value!!.policy!!
        "${when (facts.source) { "catalog" -> "运营商目录"; "diagnostic" -> "诊断布局"; else -> "手动设置" }} · ${if (policy.layout == ConfigurationTunnelLayout.MULTIPLE_COMPLETE) "完整多报价" else "合并单报价"}\n" +
            "IKE：" + policy.ikeSuites.joinToString("；", transform = ::ikeSuiteLabel) + "\nChild：" +
            policy.childSuites.joinToString("；", transform = ::childSuiteLabel) +
            "\nChild 重键：${if (policy.childPFSGroup == 14) "PFS · DH14" else "无额外 PFS"}\n这是配置快照，不是网侧协商结果"
    }
}
