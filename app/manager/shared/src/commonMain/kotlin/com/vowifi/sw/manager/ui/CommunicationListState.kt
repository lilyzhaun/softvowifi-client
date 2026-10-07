package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** A list-owned state, with the same gutters as the content it will replace. */
@Composable
internal fun CommunicationListState(spec: StatePanelSpec, action: @Composable () -> Unit = {}) {
    val colors = LocalSoftVoWiFiColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        if (spec.loading) CircularProgressIndicator(Modifier.size(24.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }) else Icon(spec.icon, null, Modifier.size(24.dp),
            tint = if (spec.tone == StatePanelTone.ERROR) colors.error else colors.textSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(spec.title, Modifier.semantics { heading() }, fontSize = 17.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.Medium, color = colors.textPrimary)
            Text(spec.description, fontSize = 14.sp, lineHeight = 20.sp, color = colors.textSecondary)
            action()
        }
    }
}

@Composable
internal fun CommunicationListLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate })
        Text(label, color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
