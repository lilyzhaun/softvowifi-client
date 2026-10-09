package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.data.MessageRemarkSaveResult
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageHistoryChoices
import com.vowifi.sw.manager.model.MessageHistoryLine
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object MessageLineLayout {
    val gap = 8.dp
    val inset = 12.dp
    val touch = 48.dp
    val viewport = 360.dp
    val body = 16.sp
    val bodyLine = 22.sp
    val secondary = 14.sp
    val secondaryLine = 20.sp
}

@Composable
internal fun MessageLineEntry(choices: MessageHistoryChoices, title: String? = null, actionLabel: String = "短信线路", onOpen: () -> Unit) {
    val selected = choices.lines.firstOrNull { it.owner == choices.selectedOwner }
    val stopped = choices.hasRunningEngines && selected != null && !selected.running
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CommunicationLineChip(title ?: selected?.lineTitle() ?: "选择短信线路", actionLabel, onOpen)
        if (stopped) Text("原线路已停止，请切换运行线路；原草稿保持不变。",
            color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        if (choices.labelsFailed || choices.labelsLoading) {
            Text(if (choices.labelsFailed) "无法读取线路备注，点此重试" else "正在读取线路备注",
                color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun MessageLinePicker(
    choices: MessageHistoryChoices,
    onDismiss: () -> Unit,
    onSelect: (SimOwnerId) -> Unit,
    onEdit: (MessageHistoryLine) -> Unit,
    onRetry: (() -> Unit)?,
) {
    var query by remember { mutableStateOf("") }
    val lines = filterMessageLines(choices.selectableLines(), query)
    val listState = rememberLazyListState()
    val scroll = rememberSmsScrollFeedback(listState.canScrollBackward || listState.canScrollForward)
    val sheet = rememberCommunicationSheetState(onDismiss)
    val interactive = sheet.visible && LocalAppRouteActive.current
    CommunicationSheet(choices.pickerTitle(), onDismiss, state = sheet) {
             CommunicationInput(query, { query = it }, "搜索SIM、备注或历史编号",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = interactive,
                singleLine = true, leading = CommunicationSearch)
            CommunicationFootnote(if (choices.hasRunningEngines) "仅显示已启动引擎的线路；切换同时显示该线路的短信，已有会话仍用原线路回复。"
                else "切换该线路的本地短信记录，可包含未注册或历史线路。")
            when {
                choices.labelsFailed -> {
                    Text("无法读取线路备注", color = LocalSoftVoWiFiColors.current.error)
                    if (onRetry != null) CommunicationTextAction("重新读取", onRetry, enabled = interactive)
                }
                choices.labelsLoading -> Text("正在读取线路备注")
                lines.isEmpty() -> Text("没有匹配的线路")
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = MessageLineLayout.viewport).selectableGroup()
                    .then(scroll.modifier), state = listState, overscrollEffect = scroll.effect) {
                    items(lines, key = { it.owner.wire() }) { line ->
                        MessageLineRow(line, line.owner == choices.selectedOwner,
                            { if (interactive) sheet.close { onSelect(line.owner) } },
                            { if (interactive) sheet.close { onEdit(line) } }, enabled = interactive)
                    }
                }
            }
    }
}

@Composable
private fun MessageLineRow(line: MessageHistoryLine, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit,
    enabled: Boolean = true) {
    CommunicationLineRow(line.lineTitle(), line.lineSummary(), selected, onSelect, onEdit, enabled)
}

@Composable
internal fun MessageRemarkDialog(editor: MessageRemarkEditor, onDismiss: () -> Unit,
    submit: suspend (SimOwnerId, String) -> MessageRemarkSaveResult) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val scroll = rememberSmsScrollFeedback(scrollState.canScrollBackward || scrollState.canScrollForward)
    val sheet = rememberCommunicationSheetState(onDismiss)
    val interactive = sheet.visible && LocalAppRouteActive.current
    CommunicationSheet("线路备注", onDismiss, state = sheet, dismissible = !editor.busy,
        endAction = { CommunicationTextAction(if (editor.busy) "正在保存" else "保存",
            { if (sheet.visible) scope.launch { if (editor.save(submit)) sheet.close(onDismiss) } },
            enabled = interactive && !editor.busy, weight = FontWeight.SemiBold) }) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).then(scroll.modifier).verticalScroll(scrollState, overscrollEffect = scroll.effect).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CommunicationFootnote("${editor.simLabel ?: editor.lineNumber?.let { "历史线路 $it" } ?: "线路"} · 备注仅保存在本机")
                CommunicationInput(editor.input, { editor.input = it }, "备注",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = interactive && !editor.busy)
                val input = editor.input.trim()
                Text("${input.codePointCount(0, input.length)} / 40 字符", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = MessageLineLayout.secondary, lineHeight = MessageLineLayout.secondaryLine)
                editor.error?.let { Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = MessageLineLayout.secondary, lineHeight = MessageLineLayout.secondaryLine,
                    color = LocalSoftVoWiFiColors.current.error) }
                CommunicationFootnote("清除后显示运营商名称；同名线路以后四位区分")
                 CommunicationTextAction("清除备注", {
                    if (sheet.visible && !editor.busy) {
                        editor.input = ""
                        scope.launch { if (editor.save(submit)) sheet.close(onDismiss) }
                    }
                }, enabled = interactive && !editor.busy, modifier = Modifier.fillMaxWidth(), destructive = true)
            }
    }
}
