package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.ConfigurationEpdgSelectionCodec
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal enum class EpdgCandidateField { VALUE, REGISTERED_PLMNS }
internal data class EpdgCandidateTextEdit(val index: Int, val field: EpdgCandidateField, val openCount: Long)

@Composable
internal fun ConfigurationEpdgSelectionTextDialog(edit: EpdgCandidateTextEdit?, draft: ConfigurationEditorDraft, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<EpdgCandidateTextEdit?>(null) }
    SideEffect { if (edit != null) last = edit }
    val current = edit ?: last ?: return
    val row = draft.epdgSelection?.candidates?.getOrNull(current.index) ?: return
    val title = if (current.field == EpdgCandidateField.VALUE) "候选值" else "注册 PLMN 限定"
    val input = remember(current) { TextFieldState(if (current.field == EpdgCandidateField.VALUE) row.value else row.registeredPlmns.joinToString(",")) }
    val focus = remember(current) { FocusRequester() }
    var attempted by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current, edit != null) { if (edit != null) {
        withFrameNanos { }
        try { focus.requestFocus() } catch (_: IllegalStateException) { }
    } }
    val candidate = try {
        val raw = input.text.toString().trim()
        val value = if (current.field == EpdgCandidateField.VALUE) row.copy(value = raw)
            else row.copy(registeredPlmns = if (raw.isEmpty()) emptyList() else raw.split(Regex("[,，\\s]+")).sorted())
        ConfigurationEpdgSelectionCodec.validate(ConfigurationEpdgSelection(listOf(value)))
        value
    } catch (_: ConfigurationFormatException) { null }
    val error = if (attempted && candidate == null) "请核对候选值与 PLMN；不接受端口、重复项或敏感标识" else null
    OverlayDialog(show = edit != null, title = title,
        summary = if (current.field == EpdgCandidateField.REGISTERED_PLMNS) "最多 8 个 5 或 6 位 PLMN，以逗号分隔；留空不限。仅匹配已确认的注册网，不改变候选顺序。"
            else if (row.kind == ConfigurationEpdgKind.PLMN) "填写 5 或 6 位 PLMN，保留 MNC 原始长度。确认只修改草稿。"
            else "填写域名或 IP 字面值，不含协议、端口或路径。确认只修改草稿。", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = input, label = title, useLabelAsPlaceholder = false, lineLimits = TextFieldLineLimits.SingleLine,
                enabled = edit != null && !draft.pending, modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier))
            if (error != null) Text(error, color = LocalSoftVoWiFiColors.current.error)
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    attempted = true
                    val rows = draft.epdgSelection?.candidates
                    if (edit != null && candidate != null && rows?.getOrNull(current.index) == row &&
                        draft.replaceEpdgSelection(ConfigurationEpdgSelection(rows.toMutableList().apply { set(current.index, candidate) }))) onDismiss()
                }, modifier = m, enabled = edit != null && !draft.pending, primary = true) })
        }
    }
}
