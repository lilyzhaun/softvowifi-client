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
internal fun MessageLineEntry(choices: MessageHistoryChoices, onOpen: () -> Unit) {
    val selected = choices.lines.firstOrNull { it.owner == choices.selectedOwner }
    val feedback = managerActionFeedback(ManagerAction.Navigate)
    CompositionLocalProvider(LocalIndication provides NoVisualIndication) {
        ArrowPreference(
            title = selected?.lineTitle() ?: "短信线路",
            summary = when {
                choices.labelsFailed -> "无法读取线路备注，点此重试"
                choices.labelsLoading -> "正在读取线路备注"
                selected != null -> selected.lineSummary()
                else -> "选择要浏览的线路"
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = MessageLineLayout.gap)
                .heightIn(min = MessageLineLayout.touch).managerPressScale(feedback),
            onClick = rememberManagerActionHandler(feedback, onOpen),
        )
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
    val lines = filterMessageLines(choices.lines, query)
    OverlayDialog(show = true, title = "选择短信线路", summary = "仅切换浏览历史，不切换发送线路", onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(MessageLineLayout.gap)) {
            TextField(value = query, onValueChange = { query = it }, label = "搜索备注或本地编号", modifier = Modifier.fillMaxWidth())
            when {
                choices.labelsFailed -> {
                    Text("无法读取线路备注", color = LocalSoftVoWiFiColors.current.error)
                    if (onRetry != null) ManagerTextAction("重新读取", ManagerAction.Refresh, onRetry)
                }
                choices.labelsLoading -> Text("正在读取线路备注")
                lines.isEmpty() -> Text("没有匹配的线路")
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = MessageLineLayout.viewport).selectableGroup()) {
                    items(lines, key = { it.owner.wire() }) { line ->
                        MessageLineRow(line, line.owner == choices.selectedOwner,
                            { onSelect(line.owner) }, { onEdit(line) })
                    }
                }
            }
            ManagerTextAction("取消", ManagerAction.Rejected, onDismiss)
        }
    }
}

@Composable
private fun MessageLineRow(line: MessageHistoryLine, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val feedback = managerActionFeedback(ManagerAction.SegmentSelection)
    val handler = rememberManagerActionHandler(feedback, onSelect)
    val colors = LocalSoftVoWiFiColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = MessageLineLayout.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = MessageLineLayout.touch)
                .managerPressScale(feedback, interaction)
                .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = handler)
                .padding(MessageLineLayout.inset),
                horizontalArrangement = Arrangement.spacedBy(MessageLineLayout.gap), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(line.lineTitle(), fontSize = MessageLineLayout.body, lineHeight = MessageLineLayout.bodyLine)
                    Text(line.lineSummary(), color = colors.textSecondary, fontSize = MessageLineLayout.secondary, lineHeight = MessageLineLayout.secondaryLine)
                }
            }
            ManagerTextAction("备注", ManagerAction.ContextAction, onEdit)
        }
        HorizontalDivider(color = colors.separator)
    }
}

@Composable
internal fun MessageRemarkDialog(editor: MessageRemarkEditor, onDismiss: () -> Unit,
    submit: suspend (SimOwnerId, String) -> MessageRemarkSaveResult) {
    val scope = rememberCoroutineScope()
    OverlayDialog(show = true,
        onDismissRequest = { if (!editor.busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MessageLineLayout.gap)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MessageLineLayout.gap)) {
                Text(editor.lineNumber?.let { "本地线路 $it 的备注" } ?: "线路备注",
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    fontSize = MiuixTheme.textStyles.title4.fontSize, fontWeight = FontWeight.Medium)
                Text("备注仅保存在本机", modifier = Modifier.fillMaxWidth(),
                    fontSize = MessageLineLayout.secondary, lineHeight = MessageLineLayout.secondaryLine,
                    color = LocalSoftVoWiFiColors.current.textSecondary)
                TextField(value = editor.input, onValueChange = { editor.input = it }, label = "备注",
                    enabled = !editor.busy, modifier = Modifier.fillMaxWidth())
                val input = editor.input.trim()
                Text("${input.codePointCount(0, input.length)} / 40 字符", modifier = Modifier.fillMaxWidth(),
                    fontSize = MessageLineLayout.secondary, lineHeight = MessageLineLayout.secondaryLine)
                editor.error?.let { Text(it, modifier = Modifier.fillMaxWidth(),
                    fontSize = MessageLineLayout.secondary, lineHeight = MessageLineLayout.secondaryLine,
                    color = LocalSoftVoWiFiColors.current.error) }
                Text("清除后使用本地编号", modifier = Modifier.fillMaxWidth(),
                    fontSize = MessageLineLayout.secondary, lineHeight = MessageLineLayout.secondaryLine,
                    color = LocalSoftVoWiFiColors.current.textSecondary)
                ManagerTextAction("清除备注", ManagerAction.ContextAction, {
                    editor.input = ""
                    scope.launch { if (editor.save(submit)) onDismiss() }
                }, enabled = !editor.busy, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MessageLineLayout.gap)) {
                ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, enabled = !editor.busy, modifier = Modifier.weight(1f))
                ManagerTextAction(if (editor.busy) "正在保存" else "保存", ManagerAction.SaveConfiguration,
                    { scope.launch { if (editor.save(submit)) onDismiss() } }, busy = editor.busy, modifier = Modifier.weight(1f))
            }
        }
    }
}
