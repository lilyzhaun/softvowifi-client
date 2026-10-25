package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Keep the management warning in miuix's own dialog language; only the decision is app-owned. */
@Composable
internal fun HomeEngineStopConfirmation(message: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val visible = remember { mutableStateOf(true) }
    val active = rememberUpdatedState(LocalAppRouteActive.current)
    val cancel = rememberUpdatedState(onCancel)
    val confirm = rememberUpdatedState(onConfirm)
    val decision = remember { mutableStateOf<Boolean?>(null) }
    val confirmPulse = rememberManagerActionHandler(
        ManagerActionFeedback(HapticFeedbackType.Confirm, ManagerActionFeedback.PressScale)) {}
    val cancelPulse = rememberManagerActionHandler(managerActionFeedback(ManagerAction.Rejected)) {}
    val close: (Boolean) -> Unit = { accepted ->
        if (visible.value && active.value) {
            decision.value = accepted
            visible.value = false
            if (accepted) confirmPulse() else cancelPulse()
        }
    }
    LaunchedEffect(active.value) {
        if (!active.value) { decision.value = false; visible.value = false }
    }
    OverlayDialog(
        show = visible.value,
        modifier = Modifier.semantics { dialog(); paneTitle = "停止引擎？" },
        title = "停止引擎？",
        summary = message,
        onDismissRequest = { close(false) },
        onDismissFinished = {
            val accepted = decision.value
            decision.value = null
            if (accepted != null) {
                if (accepted && active.value) confirm.value() else cancel.value()
            }
        },
    ) {
        Row {
            TextButton("取消", { close(false) }, Modifier.weight(1f), minHeight = 48.dp,
                enabled = visible.value && active.value)
            Spacer(Modifier.width(20.dp))
            TextButton("停止", { close(true) }, Modifier.weight(1f), minHeight = 48.dp,
                enabled = visible.value && active.value,
                colors = ButtonDefaults.textButtonColors(textColor = LocalSoftVoWiFiColors.current.error))
        }
    }
}
