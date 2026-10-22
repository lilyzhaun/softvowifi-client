package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogMessage
import com.vowifi.sw.manager.model.OperationalLogOrigin
import com.vowifi.sw.manager.model.operationalLogSafeSummary
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun LogEntryRow(entry: OperationalLogEntry, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    var detail by remember(entry) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val actionable = LocalAppRouteActive.current
    val feedback = managerActionFeedback(ManagerAction.ContextAction, actionable)
    if (detail) LogDetailDialog(entry, onDismiss = { detail = false })
    Card(
        modifier = modifier.fillMaxWidth()
            .managerKeyboardFocusRing(interaction, actionable, cornerRadius = CardDefaults.CornerRadius)
            .managerPressInputGate(actionable)
            .clickable(enabled = actionable, interactionSource = interaction,
                indication = rememberManagerPressIndication(ManagerPressStyle.Card, actionable), onClickLabel = "查看日志详情",
                onClick = rememberManagerActionHandler(feedback) { detail = true }),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(operationalLogEntryTitle(entry), modifier = Modifier.weight(1f),
                    color = colors.textPrimary, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                if (entry.level != OperationalLogLevel.INFO) SemanticStatusBadge(logEntrySemanticBadgeSpec(entry))
            }
            Text(operationalLogEntryContext(entry), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            Text(operationalLogEntrySummary(entry), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            if (operationalLogEntryMessage(entry).removeSuffix("。") != operationalLogEntryTitle(entry)) {
            Text(
                operationalLogEntryMessage(entry),
                modifier = Modifier.padding(top = 4.dp),
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = logsChromeContract().entryBodyMaxLines,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
            )
            }
        }
    }
}

internal fun operationalLogEntryTitle(entry: OperationalLogEntry): String = entry.headline ?: when (entry.message) {
    OperationalLogMessage.EVENT -> "${operationalLogKindLabel(entry.kind)}事件"
    OperationalLogMessage.UNAVAILABLE -> "未分类事件"
    else -> operationalLogMessageLabel(entry.message)
}

internal fun operationalLogEntrySummary(entry: OperationalLogEntry): String =
    entry.timestamp.take(19) + entry.timeZoneLabel?.let { " $it" }.orEmpty()

internal fun operationalLogEntryContext(entry: OperationalLogEntry): String {
    val source = when (entry.origin) {
        OperationalLogOrigin.DIAGNOSTIC -> "应用运行"
        OperationalLogOrigin.OBSERVER -> "系统 IMS"
        OperationalLogOrigin.STARTUP -> "进程输出"
        OperationalLogOrigin.ENGINE -> "软件引擎"
    }
    val line = entry.displayLabel.takeUnless { it in setOf("全局诊断", "Root 诊断") || it.isBlank() }
    return listOfNotNull(source, line).joinToString(" · ")
}

internal fun isKeyLogEntry(entry: OperationalLogEntry): Boolean = !entry.technical || entry.level != OperationalLogLevel.INFO

internal fun operationalLogEntryMessage(entry: OperationalLogEntry): String = operationalLogSafeSummary(entry)

internal fun logEntrySemanticBadgeSpec(entry: OperationalLogEntry): SemanticStatusBadgeSpec =
    semanticStatusBadge(
        label = operationalLogLevelLabel(entry.level),
        tone = when (entry.level) {
            OperationalLogLevel.INFO -> SemanticTone.NEUTRAL
            OperationalLogLevel.WARN -> SemanticTone.WARNING
            OperationalLogLevel.ERROR -> SemanticTone.ERROR
        },
    )

internal enum class OperationalLogTone { INFORMATION, WARNING, ERROR }

internal fun operationalLogTone(level: OperationalLogLevel): OperationalLogTone = when (level) {
    OperationalLogLevel.INFO -> OperationalLogTone.INFORMATION
    OperationalLogLevel.WARN -> OperationalLogTone.WARNING
    OperationalLogLevel.ERROR -> OperationalLogTone.ERROR
}

internal fun operationalLogLevelLabel(level: OperationalLogLevel): String = when (level) {
    OperationalLogLevel.INFO -> "信息"
    OperationalLogLevel.WARN -> "告警"
    OperationalLogLevel.ERROR -> "错误"
}

internal fun operationalLogKindLabel(kind: OperationalLogKind): String = when (kind) {
    OperationalLogKind.DIAGNOSTIC -> "诊断"
    OperationalLogKind.ENGINE -> "引擎"
    OperationalLogKind.TUNNEL -> "隧道"
    OperationalLogKind.IMS -> "IMS"
}

internal fun operationalLogMessageLabel(message: OperationalLogMessage): String = when (message) {
    OperationalLogMessage.ENGINE_STARTED -> "引擎已启动"
    OperationalLogMessage.ENGINE_STOPPED -> "引擎已停止"
    OperationalLogMessage.TUNNEL_CONNECTING -> "正在建立隧道"
    OperationalLogMessage.TUNNEL_CONNECTED -> "隧道已连接"
    OperationalLogMessage.TUNNEL_DISCONNECTED -> "隧道已断开"
    OperationalLogMessage.IMS_REGISTERING -> "IMS 正在注册"
    OperationalLogMessage.IMS_REGISTERED -> "IMS 已注册"
    OperationalLogMessage.IMS_UNREGISTERED -> "IMS 未注册"
    OperationalLogMessage.NEEDS_ATTENTION -> "操作需要注意"
    OperationalLogMessage.OPERATION_FAILED -> "操作未完成"
    OperationalLogMessage.EVENT -> "运行事件"
    OperationalLogMessage.UNAVAILABLE -> "未分类事件"
}
