package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal fun LazyListScope.configurationTunnelTimingSections(instance: ConfigurationInstance, draft: ConfigurationEditorDraft,
    enabled: Boolean, onEdit: (TunnelTimingField) -> Unit) {
    item(key = "configuration-timing-title") { ConfigurationSectionTitle("隧道期限与保活") }
    item(key = "configuration-timing-policy") { ConfigurationTunnelTimingNotice(instance, draft, enabled) }
    if (instance.tunnelTiming == null || draft.tunnelTiming?.policy == null) return
    TunnelTimingField.entries.groupBy { it.group }.entries.forEachIndexed { index, (title, fields) ->
        item(key = "configuration-timing-title-$index") { ConfigurationSectionTitle(title) }
        item(key = "configuration-timing-fields-$index") {
            ConfigurationSectionCard {
                fields.forEach { field -> ConfigurationTimingRow(instance, draft, field, enabled, onEdit) }
            }
        }
    }
    item(key = "configuration-timing-limits") {
        ConfigurationFootnote("本组为本地策略，不是网侧协商寿命。完整填写后整组保存；每种 SA 只有成功替换才重新计时，地址更新不延长寿命。")
    }
}

@Composable
private fun ConfigurationTunnelTimingNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = if (instance.tunnelTiming == null) "隧道期限尚未完整确认" else "期限策略 · 下次启动生效", summary = when {
            instance.tunnelTiming == null && draft.tunnelTiming == null -> "请刷新核对配套引擎；也可明确清除整组后保存，不补猜旧值。"
            instance.tunnelTiming == null -> "已选择清除整组。请先保存并读回确认，再新建期限；其它草稿保留。"
            draft.tunnelTiming?.policy == null -> "未设置手动期限；优先继承目录，无目录组时使用兼容计时。新建草稿的 IKE 与 Child 硬期限须自行填写。"
            else -> "软期限触发重键，硬期限独立终止；两者不能互相替代。运行时保存会重启本实例。" + if (draft.timingModified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
        })
        instance.facts?.tunnelTiming?.let { BasicComponent(title = "当前解析的隧道期限", summary = timingFactsSummary(it)) }
        BasicComponent(title = "已加载的隧道期限", summary = instance.runtimeFacts?.loaded?.tunnelTiming?.let(::timingFactsSummary)
            ?: "未知；不使用磁盘配置推断运行快照")
        draft.timingError?.let { Text(it, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(it)) }
        if (instance.tunnelTiming != null && draft.tunnelTiming == ConfigurationTunnelTiming())
            ManagerTextAction("新建隧道期限", ManagerAction.ContextAction, {
                if (actionable && draft.tunnelTiming == ConfigurationTunnelTiming())
                    draft.replaceTunnelTiming(ConfigurationTunnelTiming(ConfigurationTunnelTimingPolicy()))
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        if (draft.tunnelTiming != ConfigurationTunnelTiming())
            ManagerTextAction("清除隧道期限", ManagerAction.ContextAction, { if (actionable) clearing = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组隧道期限？",
        summary = "移除手动整组并恢复目录继承；无目录组时使用兼容计时。保留其它草稿，仍需保存；运行时保存会重启本实例。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceTunnelTiming(ConfigurationTunnelTiming())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

@Composable
private fun ConfigurationTimingRow(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, field: TunnelTimingField,
    enabled: Boolean, onEdit: (TunnelTimingField) -> Unit) {
    val policy = draft.tunnelTiming?.policy ?: return
    val actionable = enabled && LocalAppRouteActive.current && !draft.pending
    val modified = field.value(policy) != instance.tunnelTiming?.policy?.let(field::value)
    val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
    val handler = rememberManagerActionHandler(if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None) { onEdit(field) }
    CompositionLocalProvider(LocalIndication provides indication) {
        ArrowPreference(title = field.title, summary = "${field.display(policy)}\n${field.description}",
            summaryColor = configurationSummaryColor(modified, false), onClick = handler, enabled = actionable,
            modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget).managerPressInputGate(actionable)
                .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }))
    }
}

internal fun timingFactsSummary(facts: ConfigurationTunnelTimingFacts): String = when {
    !facts.known -> "未知；请刷新核对"
    facts.compatibilityBaseline -> "兼容计时；未设置显式硬期限，不是网侧协商结果"
    else -> {
        val p = facts.value!!.policy!!
        "${if (facts.source == "catalog") "运营商目录" else "手动设置"} · 软 / 硬 / 提前量（秒）\nIKE：${p.ike.softSeconds} / ${p.ike.hardSeconds} / ${p.ike.jitterSeconds}\n" +
            "Child：${p.child.softSeconds} / ${p.child.hardSeconds} / ${p.child.jitterSeconds}\n" +
            "重新认证 ${p.reauthSeconds} 秒 · DPD ${p.dpdSeconds} 秒\nNAT ${p.natKeepaliveSeconds} 秒 · 余量 ${p.natDpdMarginSeconds} 秒\n" +
            "重传 ${p.retransmit.retries} 次 · 初次 ${p.retransmit.initialMillis} 毫秒 · 上限 ${p.retransmit.maximumMillis} 毫秒 · ${p.retransmit.backoffFactor} 倍\n" +
            "这是配置快照，不是网侧协商结果"
    }
}
