package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogFilter
import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

internal data class LogFilterPresentation(
    val files: List<OperationalLogFile>,
    val selectedFileId: String,
    val filter: OperationalLogFilter,
)

internal data class LogFilterActions(
    val onSelectFile: (OperationalLogFile) -> Unit,
    val onKindSelected: (OperationalLogKind?) -> Unit,
    val onLevelSelected: (OperationalLogLevel?) -> Unit,
)

@Composable
internal fun LogFilterRow(
    presentation: LogFilterPresentation,
    actions: LogFilterActions,
    modifier: Modifier = Modifier,
) {
    val files = presentation.files.groupBy { it.groupKey?.let { group -> "group:$group" } ?: "file:${it.id}" }
        .values.map { members -> members.firstOrNull { it.name == "engine.log" } ?: members.first() }
    val selectedFileId = presentation.selectedFileId
    val selectedGroup = presentation.files.firstOrNull { it.id == selectedFileId }?.groupKey
    val filter = presentation.filter
    val labels = files.map(::operationalLogFileLabel)
    val fileOptions = files.mapIndexed { index, file -> labels[index] + if (labels.count { it == labels[index] } > 1)
        " · ${if (file.groupKey == null) "旧文件" else "线路"}${index + 1}" else "" }
    val selectedFileIndex = files.indexOfFirst { it.id == selectedFileId || (selectedGroup != null && it.groupKey == selectedGroup) }
    val hasSelectedFile = selectedFileIndex >= 0
    val kindOptions = operationalLogKindOptions()
    val levelOptions = operationalLogLevelOptions()
    val fileItems = when {
        files.isEmpty() -> listOf("暂无可用日志")
        hasSelectedFile -> fileOptions
        else -> listOf("未选择日志") + fileOptions
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(vertical = 4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (presentation.files.any { it.groupKey == null && it.id !in setOf("app-diagnostics-v1","root-diagnostics-v1","all-diagnostics-v1") }) Text(
                "当前模块未提供安全分组，仅按文件读取；尚不支持合并同线路日志。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OverlayDropdownPreference(
                items = fileItems,
                selectedIndex = if (hasSelectedFile) selectedFileIndex else 0,
                title = "日志文件",
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                enabled = files.isNotEmpty(),
                onSelectedIndexChange = { index ->
                    files.getOrNull(if (hasSelectedFile) index else index - 1)?.let(actions.onSelectFile)
                },
            )
            OverlayDropdownPreference(
                title = "类型",
                items = kindOptions.map { it.second },
                selectedIndex = kindOptions.indexOfFirst { it.first == filter.kind }.coerceAtLeast(0),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onSelectedIndexChange = { index -> kindOptions.getOrNull(index)?.let { actions.onKindSelected(it.first) } },
            )
            OverlayDropdownPreference(
                title = "级别",
                items = levelOptions.map { it.second },
                selectedIndex = levelOptions.indexOfFirst { it.first == filter.level }.coerceAtLeast(0),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onSelectedIndexChange = { index -> levelOptions.getOrNull(index)?.let { actions.onLevelSelected(it.first) } },
            )
        }
    }
}

internal fun operationalLogKindOptions(): List<Pair<OperationalLogKind?, String>> = listOf(
    null to "全部",
    OperationalLogKind.ENGINE to "引擎",
    OperationalLogKind.TUNNEL to "隧道",
    OperationalLogKind.IMS to "IMS",
    OperationalLogKind.DIAGNOSTIC to "诊断",
)

internal fun operationalLogLevelOptions(): List<Pair<OperationalLogLevel?, String>> = listOf(
    null to "全部",
    OperationalLogLevel.INFO to "信息",
    OperationalLogLevel.WARN to "告警",
    OperationalLogLevel.ERROR to "错误",
)

internal fun operationalLogFileLabel(file: OperationalLogFile): String {
    if (file.id == "app-diagnostics-v1" && file.name == "app.jsonl") return "App 全局诊断"
    if (file.id == "root-diagnostics-v1" && file.name == "diagnostic.jsonl") return "Root 全链路诊断"
    if (file.id == "all-diagnostics-v1" && file.name == "combined.jsonl") return "App＋Root 统一诊断"
    val suffix = file.suffix.filter(Char::isLetterOrDigit).takeLast(4)
    return if (suffix.isEmpty()) "运行日志 ••••" else "运行日志 •••• $suffix"
}
