package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** One compact source notice; full capture/read diagnostics are disclosed on demand. */
@Composable
internal fun LogReadNotice(spec: StatePanelSpec, onRetry: (() -> Unit)?, retryEnabled: Boolean, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val colors = LocalSoftVoWiFiColors.current
    Card(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(spec.icon, null, modifier = Modifier.size(18.dp),
                    tint = if (spec.tone == StatePanelTone.ERROR) colors.error else colors.warning)
                Text(spec.title, modifier = Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp)
            }
            if (expanded) Text(spec.description, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ManagerTextAction(if (expanded) "收起说明" else "查看说明", ManagerAction.ContextAction, { expanded = !expanded })
                if (onRetry != null) ManagerTextAction("重试读取日志", ManagerAction.Refresh, onRetry, enabled = retryEnabled)
            }
        }
    }
}
