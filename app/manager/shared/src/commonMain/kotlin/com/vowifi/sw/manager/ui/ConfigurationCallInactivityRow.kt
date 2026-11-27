package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationCallInactivity
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun ConfigurationCallInactivityRow(draft: ConfigurationEditorDraft, enabled: Boolean) {
    var editing by remember(draft) { mutableStateOf(false) }
    var sequence by remember(draft) { mutableLongStateOf(0L) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(draft.callMedia, actionable) { editing = false }
    val policy = draft.callMedia!!.inactivity
    val state = remember(sequence) { TextFieldState(policy?.timeoutMs?.toString().orEmpty()) }
    var attempted by remember(sequence) { mutableStateOf(false) }
    val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
    val handler = rememberManagerActionHandler(if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None) {
        if (actionable) { sequence++; editing = true }
    }
    CompositionLocalProvider(LocalIndication provides indication) {
        ArrowPreference(title = "媒体静默超时", summary = when {
            policy == null -> "本组未启用"
            policy.timeoutMs == 0 -> "显式禁用"
            else -> "合法媒体与控制同时静默 ${policy.timeoutMs} ms 后挂断"
        }, summaryColor = configurationSummaryColor(draft.mediaModified, false), enabled = actionable, onClick = handler,
            modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget).managerPressInputGate(actionable)
                .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }))
    }
    OverlayDialog(show = editing && actionable, title = "媒体静默超时",
        summary = "仅下一通生效。双向媒体中，合法 RTP 与 RTCP 同时静默才挂断。早期、非双向媒体和 RTCP 全禁用时不触发；不等于通话质量阈值。",
        onDismissRequest = { editing = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = state, label = "超时（ms）", useLabelAsPlaceholder = false,
                lineLimits = TextFieldLineLimits.SingleLine, enabled = actionable && editing, modifier = Modifier.fillMaxWidth())
            if (attempted) {
                val error = "请输入 0–86400000 的整数毫秒；0 禁用，留空移除本组超时覆盖"
                Text(error, color = LocalSoftVoWiFiColors.current.error,
                    modifier = Modifier.fillMaxWidth().configurationErrorSemantics(error))
            }
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { editing = false }, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    if (actionable && editing) {
                        val text = state.text.toString().trim()
                        val value = text.toIntOrNull()?.takeIf { it in 0..86400000 }
                        attempted = text.isNotEmpty() && value == null
                        if (!attempted && draft.replaceCallMedia(draft.callMedia!!.copy(inactivity = value?.let(::ConfigurationCallInactivity)))) editing = false
                    }
                }, modifier = m, enabled = actionable && editing, primary = true) })
        }
    }
}
