package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun ConfigurationImsTimingTextDialog(edit: ImsTimingTextEdit?, draft: ConfigurationEditorDraft, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<ImsTimingTextEdit?>(null) }
    SideEffect { if (edit != null) last = edit }
    val current = edit ?: last ?: return
    val input = remember(current) { TextFieldState(draft.imsTiming?.policy?.let(current.field::value)?.toString().orEmpty()) }
    val focus = remember(current) { FocusRequester() }
    var attempted by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current, edit != null) { if (edit != null) {
        withFrameNanos { }
        try { focus.requestFocus() } catch (_: IllegalStateException) { }
    } }
    OverlayDialog(show = edit != null, title = current.field.title,
        summary = "单位：${current.field.unit}。${current.field.description}\n确认只修改草稿，保存时核对完整组。", onDismissRequest = onDismiss) {
        val candidate = try { draft.imsTiming?.policy?.let { current.field.withText(it, input.text.toString()) } }
        catch (_: ConfigurationFormatException) { null }
        val error = if (attempted && candidate == null) "请输入有效数值；单位和范围见上方说明" else null
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = input, label = current.field.title, useLabelAsPlaceholder = false,
                lineLimits = TextFieldLineLimits.SingleLine, enabled = edit != null && !draft.pending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier))
            if (error != null) Text(error, color = LocalSoftVoWiFiColors.current.error)
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    attempted = true
                    if (edit != null && candidate != null && draft.replaceImsTiming(ConfigurationImsTiming(candidate))) onDismiss()
                }, modifier = m, enabled = edit != null && !draft.pending, primary = true) })
        }
    }
}
