package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.ConfigurationTunnelIdentityCodec
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun ConfigurationTunnelIdentityDialog(edit: Long?, draft: ConfigurationEditorDraft, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<Long?>(null) }
    SideEffect { if (edit != null) last = edit }
    val current = edit ?: last ?: return
    val policy = draft.tunnelIdentity?.policy ?: return
    val peer = policy.peer ?: return
    val input = remember(current) { TextFieldState(peer.value) }
    val focus = remember(current) { FocusRequester() }
    var attempted by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current, edit != null) { if (edit != null) {
        withFrameNanos { }
        try { focus.requestFocus() } catch (_: IllegalStateException) { }
    } }
    val candidate = try {
        policy.copy(peer = peer.copy(value = input.text.toString())).also(ConfigurationTunnelIdentityCodec::validate)
    } catch (_: ConfigurationFormatException) { null }
    val error = if (attempted && candidate == null) "请填写有效的完整身份值，最多 253 字符；KEY_ID 使用标准 Base64，不接受敏感标识" else null
    OverlayDialog(show = edit != null, title = "预期对端身份",
        summary = "类型：${ikeIdLabel(peer.type)}。精确匹配完整值，不自动补域名、改大小写或去空格；确认只修改草稿。", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = input, label = "预期对端身份", useLabelAsPlaceholder = false, lineLimits = TextFieldLineLimits.SingleLine,
                enabled = edit != null && !draft.pending, modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier))
            if (error != null) Text(error, color = LocalSoftVoWiFiColors.current.error)
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    attempted = true
                    if (edit != null && candidate != null && draft.tunnelIdentity?.policy == policy &&
                        draft.replaceTunnelIdentity(ConfigurationTunnelIdentity(candidate))) onDismiss()
                }, modifier = m, enabled = edit != null && !draft.pending, primary = true) })
        }
    }
}
