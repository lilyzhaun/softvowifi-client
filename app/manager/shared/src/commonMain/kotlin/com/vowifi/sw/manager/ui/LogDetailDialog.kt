package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.OperationalLogEntry
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LogDetailDialog(entry: OperationalLogEntry, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = LocalSoftVoWiFiColors.current
    var copied by remember(entry) { mutableStateOf(false) }
    OverlayDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(operationalLogEntryTitle(entry), modifier = Modifier.semantics { heading() }, fontSize = MiuixTheme.textStyles.title4.fontSize)
                Text(entry.timestamp + entry.timeZoneLabel?.let { " $it" }.orEmpty(), color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Text("${operationalLogEntryContext(entry)} · ${operationalLogLevelLabel(entry.level)}",
                    color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Text(entry.safeSummary, fontSize = 16.sp, lineHeight = 22.sp, color = colors.textPrimary)
                Text("技术详情", fontSize = 14.sp, lineHeight = 20.sp)
                SelectionContainer {
                    Text(entry.safeDetail, fontSize = 16.sp, lineHeight = 22.sp, color = colors.textPrimary)
                }
                if (entry.detailTruncated) Text("显示内容已截断", color = colors.warning, fontSize = 14.sp, lineHeight = 20.sp)
            }
            ManagerTextAction(if (copied) "已复制详情" else "复制详情", ManagerAction.ContextAction, {
                scope.launch { clipboard.setClipEntry(messageTextClipEntry(entry.safeDetail)); copied = true }
            }, modifier = Modifier.fillMaxWidth())
            ManagerTextAction("关闭", ManagerAction.Rejected, onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
