package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.CallLineChoice
import com.vowifi.sw.manager.data.CallLineState
import com.vowifi.sw.manager.data.SimOwnerId
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal fun CallLineChoice.callFacts(complete: Boolean): String = listOf(
    when { active -> "SIM 已启用"; complete -> "SIM 未启用"; else -> "SIM 状态未知" },
    if (available) "引擎可用" else "引擎不可用",
    when (registered) { true -> "已注册"; false -> "未注册"; null -> "注册未知" },
    if (syncReady) "状态已同步" else "状态待确认",
).joinToString(" · ")

@Composable internal fun CallLineEntry(
    lines: CallLineState,
    label: String,
    onSelect: (SimOwnerId) -> Unit,
    enabled: Boolean = true,
) {
    var show by remember { mutableStateOf(false) }
    val feedback = managerActionFeedback(ManagerAction.Navigate, enabled)
    CompositionLocalProvider(LocalIndication provides NoVisualIndication) {
        ArrowPreference(title = "出向线路", summary = label, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).managerPressScale(feedback)
                .semantics { contentDescription = "出向线路" },
            onClick = rememberManagerActionHandler(feedback) { show = true })
    }
    if (show) OverlayDialog(show = true, title = "选择拨出线路",
        summary = "选卡保留号码，不改变通话记录或当前来电。", onDismissRequest = { show = false }) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lines.choices.isEmpty()) PhoneText("暂无线路信息，号码已保留", secondary = true)
            lines.choices.forEach { line ->
                val interaction = remember(line.owner) { MutableInteractionSource() }
                val choiceFeedback = managerActionFeedback(ManagerAction.SegmentSelection, enabled)
                val choose = rememberManagerActionHandler(choiceFeedback) { onSelect(line.owner); show = false }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).managerPressScale(choiceFeedback, interaction)
                    .selectable(line.selected, enabled = enabled, role = Role.RadioButton,
                        interactionSource = interaction, indication = null, onClick = choose).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = line.selected, onClick = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhoneText(line.label)
                        PhoneText(line.callFacts(lines.rosterComplete), size = 14, secondary = true)
                    }
                }
            }
            ManagerTextAction("取消", ManagerAction.Rejected, { show = false })
        }
    }
}
