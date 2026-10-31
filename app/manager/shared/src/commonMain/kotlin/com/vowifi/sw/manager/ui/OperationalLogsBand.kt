package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun OperationalLogsSectionGap() {
    Spacer(Modifier.height(0.dp))
}

@Composable
fun OperationalLogsBand(modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    Column(
        modifier = modifier.fillMaxWidth().background(colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "运行日志",
            color = colors.textPrimary,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "仅显示已脱敏的引擎事件。",
            color = colors.textSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            softWrap = true,
        )
    }
}

@Composable
internal fun LogExportButton(
    presentation: LogExportPresentation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val routeActive = LocalAppRouteActive.current
    val businessEnabled = presentation.enabled && !presentation.busy
    val actionable = businessEnabled && routeActive
    val feedback = managerActionFeedback(ManagerAction.ExportLogs, enabled = actionable)
    val actionHandler = rememberManagerActionHandler(feedback, onClick)
    val interaction = remember { MutableInteractionSource() }
    val indication = rememberManagerPressIndication(ManagerPressStyle.Solid, actionable)
    Button(
        onClick = actionHandler,
        enabled = actionable,
        interactionSource = interaction,
        indication = null,
        colors = ButtonDefaults.buttonColors(
            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
            disabledColor = colors.separator,
            contentColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary,
            disabledContentColor = colors.textDisabled,
        ).preservingPressBusinessColors(businessEnabled, routeActive),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                if (presentation.busy) {
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }
            }.managerKeyboardFocusRing(interaction, actionable, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary)
            .managerPressInputGate(actionable).indication(interaction, indication),
    ) {
        if (presentation.busy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(20.dp),
            )
        }
        Text(presentation.label, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    }
}
