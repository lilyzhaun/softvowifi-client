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
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal fun LazyListScope.configurationTunnelIdentitySections(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean, onPeerEdit: () -> Unit) {
    item(key = "configuration-tunnel-identity-title") { ConfigurationSectionTitle("IKE 身份策略") }
    item(key = "configuration-tunnel-identity-policy") { ConfigurationTunnelIdentitySection(instance, draft, enabled, onPeerEdit) }
}

@Composable
private fun ConfigurationTunnelIdentitySection(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean, onPeerEdit: () -> Unit) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = "身份策略 · 下次启动生效", summary = when {
            instance.tunnelIdentity == null && draft.tunnelIdentity == null -> "完整策略尚未读取，或对端值因隐私规则未显示。可刷新核对，或明确清除整组后保存。"
            instance.tunnelIdentity == null -> "已选择清除整组；保存并读回确认后可新建策略。"
            draft.tunnelIdentity?.policy == null -> "未设置手动策略；优先继承目录，无目录组时沿用兼容类型。本地身份仍取 SIM 派生 NAI，请求 IDr 沿 APN 来源。"
            else -> "只选择线格式类型；预期对端身份是独立约束，不从 ePDG 地址或 APN 推断。" + if (draft.identityModified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
        })
        BasicComponent(title = "当前解析的 IKE 身份策略", summary = identityFactsSummary(instance.facts?.tunnelIdentity))
        BasicComponent(title = "已加载的 IKE 身份策略", summary = identityFactsSummary(instance.runtimeFacts?.loaded?.tunnelIdentity))
        BasicComponent(title = "本次认证身份类型", summary = instance.runtimeFacts?.identity?.let {
            "IDi ${ikeIdLabel(it.localType)}；请求 IDr ${ikeIdLabel(it.requestType)}；对端 ${ikePeerTypeLabel(it.peerType)}\n" +
                if (it.peerConstrained) "已匹配独立对端约束" else "未设置额外对端约束"
        } ?: "未知；不以本地配置代替已认证事实")
        draft.identityError?.let { Text(it, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(it)) }
        val policy = draft.tunnelIdentity?.policy
        if (instance.tunnelIdentity != null && policy != null) {
            fun update(next: ConfigurationTunnelIdentityPolicy) {
                if (actionable && draft.tunnelIdentity?.policy == policy) draft.replaceTunnelIdentity(ConfigurationTunnelIdentity(next))
            }
            ConfigurationChoicePreference("本地 IDi 类型", listOf(ConfigurationIkeIdType.RFC822, ConfigurationIkeIdType.KEY_ID).map { ConfigurationChoice(it.wire, ikeIdLabel(it)) },
                policy.localType.wire, "身份值仍由 SIM 派生 NAI；类型选择不改内层 EAP Identity。", draft.identityModified, actionable) { selected ->
                update(policy.copy(localType = ConfigurationIkeIdType.entries.first { it.wire == selected }))
            }
            ConfigurationChoicePreference("请求 IDr 类型", listOf(ConfigurationIkeIdType.FQDN, ConfigurationIkeIdType.KEY_ID).map { ConfigurationChoice(it.wire, ikeIdLabel(it)) },
                policy.requestType.wire, "请求值沿当前 APN 来源；不等于网侧实际身份。", draft.identityModified, actionable) { selected ->
                update(policy.copy(requestType = ConfigurationIkeIdType.entries.first { it.wire == selected }))
            }
            ConfigurationChoicePreference("对端身份约束", listOf(ConfigurationChoice("", "不额外限定")) + ConfigurationIkeIdType.entries.map { ConfigurationChoice(it.wire, ikeIdLabel(it)) },
                policy.peer?.type?.wire.orEmpty(), "按类型与完整值精确匹配，不进行通配或证书信任推断。", draft.identityModified, actionable) { selected ->
                update(policy.copy(peer = if (selected.isEmpty()) null else policy.peer?.takeIf { it.type.wire == selected }
                    ?: ConfigurationPeerIdentity(ConfigurationIkeIdType.entries.first { it.wire == selected }, "")))
            }
            policy.peer?.let { peer ->
                ArrowPreference(title = "预期对端身份", summary = peer.value.ifEmpty { "未填写；${ikeIdLabel(peer.type)}" }, enabled = actionable,
                    onClick = { if (actionable) onPeerEdit() })
            }
        }
        if (instance.tunnelIdentity != null && draft.tunnelIdentity == ConfigurationTunnelIdentity()) {
            ManagerTextAction("新建 IKE 身份策略", ManagerAction.ContextAction, {
                if (actionable && draft.tunnelIdentity == ConfigurationTunnelIdentity()) draft.replaceTunnelIdentity(ConfigurationTunnelIdentity(ConfigurationTunnelIdentityPolicy()))
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        }
        if (draft.tunnelIdentity != ConfigurationTunnelIdentity()) {
            ManagerTextAction("清除 IKE 身份策略", ManagerAction.ContextAction, { if (actionable) clearing = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        }
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组 IKE 身份策略？",
        summary = "移除手动整组并恢复目录继承；无目录组时沿用兼容类型。目录的对端约束仍保留。此处只修改草稿，仍需保存；运行时保存会重启本实例。", onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceTunnelIdentity(ConfigurationTunnelIdentity())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

internal fun ikeIdLabel(type: ConfigurationIkeIdType): String = when (type) {
    ConfigurationIkeIdType.RFC822 -> "RFC822"
    ConfigurationIkeIdType.FQDN -> "FQDN"
    ConfigurationIkeIdType.KEY_ID -> "KEY_ID"
    ConfigurationIkeIdType.IPV4 -> "IPv4"
    ConfigurationIkeIdType.IPV6 -> "IPv6"
}

internal fun ikePeerTypeLabel(code: Int): String = ConfigurationIkeIdType.entries.firstOrNull { it.code == code }?.let(::ikeIdLabel) ?: "IANA $code"

internal fun identityFactsSummary(facts: ConfigurationTunnelIdentityFacts?): String = when {
    facts == null || !facts.known -> "未知；请刷新核对"
    facts.compatibilityBaseline -> "兼容类型：IDi RFC822 / 请求 IDr FQDN；无额外对端约束"
    else -> facts.value!!.let { "${if (facts.source == "catalog") "运营商目录" else "手动"}：IDi ${ikeIdLabel(it.localType)} / 请求 IDr ${ikeIdLabel(it.requestType)}；" +
        (it.peerType?.let { type -> "对端约束 ${ikeIdLabel(type)}" } ?: "无额外对端约束") }
}
