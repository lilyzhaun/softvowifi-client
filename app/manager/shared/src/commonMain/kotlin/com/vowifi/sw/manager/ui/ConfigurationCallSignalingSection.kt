package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.ConfigurationCallSignalingCodec
import com.vowifi.sw.manager.model.ConfigurationCallSignaling
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationInstance
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal fun LazyListScope.configurationCallSignalingSections(
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
    onEdit: (CallSignalingField) -> Unit,
) {
    item(key = "configuration-signaling-title") { ConfigurationSectionTitle("通话信令") }
    item(key = "configuration-signaling-note") { ConfigurationCallSignalingNotice(instance, draft, enabled) }
    if (instance.callSignaling == null) return
    val groups = listOf(
        "会话计时" to "sessionTimer.", "可靠临时响应" to "reliableProvisional.",
        "资源前置条件" to "preconditions.", "呼叫寻址与接入信息" to "addressing.",
    )
    groups.forEach { (title, prefix) ->
        item(key = "configuration-signaling-title-$prefix") { ConfigurationSectionTitle(title) }
        item(key = "configuration-signaling-$prefix") {
            ConfigurationSectionCard {
                CallSignalingField.entries.filter { it.wire.startsWith(prefix) || prefix == "addressing." && it == CallSignalingField.IncludePANI }
                    .forEach { field -> ConfigurationCallSignalingRow(instance, draft, field, enabled, onEdit) }
            }
        }
    }
    item(key = "configuration-signaling-limits") {
        ConfigurationFootnote("前置条件依赖可靠响应；本项目未实现网络 QoS 预留，无证据的本地 mandatory 要求会被拒绝。接入网络信息不是定位，不生成 PIDF 位置。")
    }
}

@Composable
private fun ConfigurationCallSignalingNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    var clearing by remember(draft) { mutableStateOf(false) }
    LaunchedEffect(instance.configurationEditAuthority(), enabled) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = if (instance.callSignaling == null) "信令覆盖尚未完整确认" else "仅用于后续新通话", summary = when {
            instance.callSignaling != null -> "单独保存此组不重启运行中的引擎，不改变当前通话；混合连接参数仍按原流程确认重启。继承值不写成手动覆盖。"
            draft.callSignaling != null -> "已选择清除整组。请先保存并读回确认，再修改单项；不会代填被隐去的旧值。"
            else -> "配套引擎未提供完整事实，或私有覆盖被隐去。请刷新核对；也可明确清除整组后保存，不会擅自拼接半组。"
        })
        draft.signalingError?.let { error ->
            Text(error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(error),
                color = LocalSoftVoWiFiColors.current.error)
        }
        if (draft.callSignaling != ConfigurationCallSignaling()) {
            ManagerTextAction("清除信令覆盖", ManagerAction.ContextAction, { if (enabled && !draft.pending) clearing = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = enabled && !draft.pending)
        }
    }
    OverlayDialog(show = clearing && enabled && !draft.pending, title = "清除整组信令覆盖？",
        summary = "只清除通话信令组并恢复继承，其它草稿项保留。此处仅修改草稿，仍需在保存栏提交；未知或被隐去的旧覆盖也会整组清除。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && enabled && !draft.pending) draft.replaceCallSignaling(ConfigurationCallSignaling())
                clearing = false
            }, modifier = m, enabled = enabled && !draft.pending, primary = true) })
    }
}

@Composable
private fun ConfigurationCallSignalingRow(instance: ConfigurationInstance, draft: ConfigurationEditorDraft,
    field: CallSignalingField, enabled: Boolean, onEdit: (CallSignalingField) -> Unit) {
    val actionable = enabled && !draft.pending && draft.callSignaling != null && LocalAppRouteActive.current
    val modified = field.value(draft.callSignaling) != field.value(instance.callSignaling)
    val summary = callSignalingFieldSummary(instance, field, modified)
    val choices = field.choices()
    if (choices != null) {
        ConfigurationChoicePreference(field.title, choices, field.value(draft.callSignaling).orEmpty(), summary, modified, actionable) { value ->
            if (actionable && !draft.pending) draft.callSignaling?.let { draft.replaceCallSignaling(field.withValue(it, value)) }
        }
    } else {
        val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
        val handler = rememberManagerActionHandler(if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None) { onEdit(field) }
        CompositionLocalProvider(LocalIndication provides indication) {
            ArrowPreference(title = field.title,
                summary = "覆盖：${field.value(draft.callSignaling)?.let(field::display) ?: "继承"}\n$summary",
                summaryColor = configurationSummaryColor(modified, false), onClick = handler, enabled = actionable,
                modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget).managerPressInputGate(actionable)
                    .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }))
        }
    }
}

@Composable
internal fun ConfigurationCallSignalingTextDialog(edit: CallSignalingTextEdit?, draft: ConfigurationEditorDraft, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<CallSignalingTextEdit?>(null) }
    SideEffect { if (edit != null) last = edit }
    val current = edit ?: last ?: return
    val input = remember(current) { TextFieldState(current.field.value(draft.callSignaling).orEmpty()) }
    val focus = remember(current) { FocusRequester() }
    var attempted by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current, edit != null) { if (edit != null) {
        withFrameNanos { }
        // Like the existing field dialog, the official overlay may attach its
        // TextField later. A missed autofocus must not crash or commit input.
        try { focus.requestFocus() } catch (_: IllegalStateException) { }
    } }
    OverlayDialog(show = edit != null, title = current.field.title, summary = current.field.description, onDismissRequest = onDismiss) {
        val candidate = signalingTextCandidate(draft.callSignaling, current.field, input.text.toString())
        val error = if (attempted && candidate == null) "参数无效或与同组设置冲突，请检查后再确认" else null
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = input, label = current.field.title, useLabelAsPlaceholder = false,
                lineLimits = TextFieldLineLimits.SingleLine, enabled = edit != null && !draft.pending,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier))
            if (error != null) Text(error, color = LocalSoftVoWiFiColors.current.error)
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    attempted = true
                    if (edit != null && candidate != null && draft.replaceCallSignaling(candidate)) onDismiss()
                }, modifier = m, enabled = edit != null && !draft.pending, primary = true) })
        }
    }
}

private fun signalingTextCandidate(group: ConfigurationCallSignaling?, field: CallSignalingField, text: String): ConfigurationCallSignaling? {
    if (group == null) return null
    return try { field.withValue(group, text).also { ConfigurationCallSignalingCodec.encode(it) } }
    catch (_: ConfigurationFormatException) { null } // Invalid user input stays in the dialog, never in the draft.
}
