package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun ManagerIconAction(
    icon: ImageVector,
    label: String,
    action: ManagerAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val feedback = managerActionFeedback(action, enabled)
    val handler = rememberManagerActionHandler(feedback, onClick)
    CompositionLocalProvider(LocalIndication provides NoVisualIndication) {
        IconButton(
            onClick = handler, enabled = enabled, minWidth = 48.dp, minHeight = 48.dp,
            modifier = modifier.managerPressScale(feedback),
        ) {
            Icon(icon, label, modifier = Modifier.size(24.dp), tint = LocalSoftVoWiFiColors.current.interactive)
        }
    }
}

@Composable
internal fun ManagerTextAction(
    label: String,
    action: ManagerAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val feedback = managerActionFeedback(action, enabled && !busy)
    val handler = rememberManagerActionHandler(feedback, onClick)
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = handler, enabled = enabled && !busy, minHeight = 48.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        interactionSource = interaction, indication = null,
        modifier = modifier.managerPressScale(feedback, interaction).semantics {
            if (busy) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp))
        Text(label, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
