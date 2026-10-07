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
import com.vowifi.sw.manager.data.CallHistoryOwnerChoice
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
    historyOwners: List<CallHistoryOwnerChoice> = emptyList(),
    onSelectHistory: (CallHistoryOwnerChoice) -> Unit = {},
) {
    var show by remember { mutableStateOf(false) }
    val active = LocalAppRouteActive.current
    val running = lines.choices.filter { it.running }
    val live = running.isNotEmpty()
    val display = if (live) lines.choices.firstOrNull { it.owner == lines.selectedOwner }?.label ?: label
        else historyOwners.firstOrNull { it.selected }?.let { history ->
            lines.choices.firstOrNull { it.owner == history.owner }?.label ?: history.label
        } ?: "选择通话记录线路"
    CommunicationLineChip(display, if (live) "出向线路" else "通话记录线路", { show = true }, enabled && active)
    if (show) {
    val sheet = rememberCommunicationSheetState { show = false }
    val interactive = enabled && active && sheet.visible
    CommunicationSheet(if (live) "选择拨出线路" else "选择通话记录线路", { show = false }, state = sheet) {
        CommunicationFootnote(if (live) "仅显示已启动引擎的线路；切换同时显示该线路的通话记录，号码保留。"
            else "切换该线路的本地通话记录，可包含未注册或历史线路。")
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()).selectableGroup()) {
            if (live) running.forEach { line ->
                 CommunicationLineRow(line.label, line.callFacts(lines.rosterComplete), line.selected,
                    { if (interactive) sheet.close { onSelect(line.owner); show = false } }, enabled = interactive)
            }
            else if (historyOwners.isEmpty()) CommunicationFootnote("暂无本地通话记录")
            else historyOwners.forEach { history ->
                val name = lines.choices.firstOrNull { it.owner == history.owner }?.label ?: history.label
                 CommunicationLineRow(name, "本机通话记录", history.selected,
                    { if (interactive) sheet.close { onSelectHistory(history); show = false } }, enabled = interactive)
            }
        }
    }
    }
}
