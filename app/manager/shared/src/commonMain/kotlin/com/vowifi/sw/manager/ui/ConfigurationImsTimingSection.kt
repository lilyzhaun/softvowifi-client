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

internal fun LazyListScope.configurationImsTimingSections(instance: ConfigurationInstance, draft: ConfigurationEditorDraft,
    enabled: Boolean, onEdit: (ImsTimingField) -> Unit) {
    item(key = "configuration-ims-timing-title") { ConfigurationSectionTitle("IMS 计时") }
    item(key = "configuration-ims-timing-policy") { ConfigurationImsTimingNotice(instance, draft, enabled) }
    if (instance.imsTiming == null || draft.imsTiming?.policy == null) return
    ImsTimingField.entries.groupBy { it.group }.entries.forEachIndexed { index, (title, fields) ->
        item(key = "configuration-ims-timing-title-$index") { ConfigurationSectionTitle(title) }
        item(key = "configuration-ims-timing-fields-$index") {
            ConfigurationSectionCard { fields.forEach { field -> ConfigurationImsTimingRow(instance, draft, field, enabled, onEdit) } }
        }
    }
    item(key = "configuration-ims-timing-limits") {
        ConfigurationFootnote("这是本地策略，不是运营商资格或注册成功。三个子组完整保存；已加载值与当前解析值分别显示。")
    }
}

@Composable
private fun ConfigurationImsTimingNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = if (instance.imsTiming == null) "IMS 计时尚未完整确认" else "IMS 计时 · 下次启动生效", summary = when {
            instance.imsTiming == null && draft.imsTiming == null -> "请刷新核对配套引擎；也可明确清除整组后保存，不补猜旧值。"
            instance.imsTiming == null -> "已选择清除整组；保存并读回确认后可新建。其它草稿保留。"
            draft.imsTiming?.policy == null -> "未设置手动计时；优先继承目录，无目录组时沿兼容计时。新建从兼容值起步，不自动复制目录；显式 T1/T2 同时用于入向响应重传。"
            else -> "事务、注册和恢复分别计时。运行时保存会重启本实例。" + if (draft.imsTimingModified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
        })
        BasicComponent(title = "当前解析的 IMS 计时", summary = imsTimingSummary(instance.facts?.imsTiming))
        BasicComponent(title = "已加载的 IMS 计时", summary = imsTimingSummary(instance.runtimeFacts?.loaded?.imsTiming))
        draft.imsTimingError?.let { Text(it, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(it)) }
        if (instance.imsTiming != null && draft.imsTiming == ConfigurationImsTiming())
            ManagerTextAction("新建 IMS 计时", ManagerAction.ContextAction, {
                if (actionable && draft.imsTiming == ConfigurationImsTiming()) draft.replaceImsTiming(ConfigurationImsTiming(ConfigurationImsTimingPolicy()))
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        if (draft.imsTiming != ConfigurationImsTiming())
            ManagerTextAction("清除 IMS 计时", ManagerAction.ContextAction, { if (actionable) clearing = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组 IMS 计时？",
        summary = "清除手动组并恢复目录继承；无目录组时沿兼容计时，保留安全策略及其它草稿。确认只修改草稿，仍需保存；运行时保存会重启本实例。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceImsTiming(ConfigurationImsTiming())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

@Composable
private fun ConfigurationImsTimingRow(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, field: ImsTimingField,
    enabled: Boolean, onEdit: (ImsTimingField) -> Unit) {
    val policy = draft.imsTiming?.policy ?: return
    val actionable = enabled && LocalAppRouteActive.current && !draft.pending
    val modified = field.value(policy) != instance.imsTiming?.policy?.let(field::value)
    val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
    val handler = rememberManagerActionHandler(if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None) { onEdit(field) }
    CompositionLocalProvider(LocalIndication provides indication) {
        ArrowPreference(title = field.title, summary = (field.value(policy)?.let { "$it ${field.unit}" } ?: "必填，尚未设置") + "\n${field.description}",
            summaryColor = configurationSummaryColor(modified, false), onClick = handler, enabled = actionable,
            modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget).managerPressInputGate(actionable)
                .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }))
    }
}

private fun imsTimingSummary(facts: ConfigurationImsTimingFacts?): String = when {
    facts == null || !facts.known -> "未知；不使用磁盘配置推断运行快照"
    facts.compatibilityBaseline -> "兼容计时；未设置手动覆盖，不是网侧授予期限"
    else -> facts.value!!.policy!!.let { p ->
        (if (facts.source == "catalog") "运营商目录" else "手动设置") + " · 事务单位为毫秒\nT1/T2/T4：${p.transaction.t1Millis} / ${p.transaction.t2Millis} / ${p.transaction.t4Millis}\n" +
            "B/D/F/H/J：${p.transaction.bMillis} / ${p.transaction.dMillis} / ${p.transaction.fMillis} / ${p.transaction.hMillis} / ${p.transaction.jMillis}\n" +
            "请求注册 ${p.registration.requestedSeconds} 秒 · 实际寿命 ${p.registration.refreshPercent}% 时刷新\n" +
            "退避 ${p.recovery.initialSeconds}–${p.recovery.maximumSeconds} 秒 · 额外不可用 ${p.recovery.unavailableSeconds} 秒"
    }
}
