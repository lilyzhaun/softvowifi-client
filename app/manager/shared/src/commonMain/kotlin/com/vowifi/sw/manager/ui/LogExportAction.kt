package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.data.DiagnosticExportWindow
import com.vowifi.sw.manager.model.OperationalLogEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun LogExportAction(actions: SettingsLogsActions, entries: List<OperationalLogEntry>, busy: Boolean) {
    var visible by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val export = logExportPresentation(actions.fullDiagnosticExport || entries.isNotEmpty(), busy)
    val active = LocalAppRouteActive.current
    val allowed = rememberUpdatedState(export.enabled && active)
    LaunchedEffect(active) {
        if (!active) { pending = null; visible = false }
    }
    ManagerTextAction("导出日志", ManagerAction.ExportLogs, {
        if (allowed.value) {
            if (actions.fullDiagnosticExport) visible = true else actions.onExport(entries)
        }
    }, enabled = export.enabled, busy = busy, busyLabel = "正在准备日志")
    OverlayBottomSheet(show = visible, title = "导出运行日志", onDismissRequest = { visible = false },
        onDismissFinished = {
            val action = pending
            pending = null
            if (allowed.value) action?.invoke()
        }) {
        Column(Modifier.navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OverlayDropdownPreference(title = "导出时间范围", items = DiagnosticExportWindow.entries.map { it.label },
                    selectedIndex = actions.exportWindow.ordinal, enabled = visible && allowed.value,
                    onSelectedIndexChange = { index -> if (visible && allowed.value)
                        DiagnosticExportWindow.entries.getOrNull(index)?.let(actions.setExportWindow) })
                Text("包含所选时间内已保留的应用与连接记录及技术步骤，不受页面筛选限制。系统 IMS 注册记录需切换到对应来源后导出。",
                    color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Text(logPrivacyNote(), color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            ManagerTextAction("选择保存位置", ManagerAction.ExportLogs, {
                if (visible && allowed.value) {
                    pending = { actions.onExport(entries) }
                    visible = false
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = visible && allowed.value, primary = true)
        }
    }
}
