package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

// Per-Scaffold, low-frequency presentation invalidation. No business state or polling lives here.
internal val LocalPopupPresentationEpoch = staticCompositionLocalOf<MutableIntState?> { null }

/** Restart only an already-closing presentation, never an in-place editor on a scale change.
 * Compose 1.11.1 caches finite-transition scale and ignores same-target spec replacement.
 * The library still owns disposal and its exactly-once dismissal handoff.
 */
@Composable internal fun ManagerPopupHost() {
    val epoch = LocalPopupPresentationEpoch.current?.intValue ?: 0
    key(epoch) { MiuixPopupHost() }
}

@Composable internal fun CommunicationSheet(title: String, onDismiss: () -> Unit,
    state: CommunicationSheetState = rememberCommunicationSheetState(onDismiss),
    dismissible: Boolean = true, closeLabel: String = "取消",
    endAction: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val colors = communicationColors()
    val interactive = state.visible && LocalAppRouteActive.current
    val focus = LocalFocusManager.current
    LaunchedEffect(interactive) { if (!interactive) focus.clearFocus() }
    val close = { if (dismissible && interactive) state.close(onDismiss) }
    OverlayBottomSheet(show = state.visible, backgroundColor = colors.grouped,
        cornerRadius = 20.dp, sheetMaxWidth = 540.dp, outsideMargin = DpSize(0.dp, 0.dp),
        insideMargin = DpSize(0.dp, 0.dp), onDismissRequest = close,
        onDismissFinished = state::finishDismiss, allowDismiss = dismissible && interactive) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding()) {
            CommunicationNavigation(title, leading = {
                CommunicationTextAction(closeLabel, close, enabled = dismissible && interactive)
            }, trailing = endAction, systemInset = false, background = false)
            content()
        }
    }
}

@Composable internal fun CommunicationConfirmation(title: String, message: String, confirmLabel: String,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    val visible = remember { mutableStateOf(true) }
    val active = rememberUpdatedState(LocalAppRouteActive.current)
    val cancel = rememberUpdatedState(onCancel); val confirm = rememberUpdatedState(onConfirm)
    val decision = remember { mutableStateOf<Boolean?>(null) }
    val confirmPulse = rememberManagerActionHandler(
        ManagerActionFeedback(HapticFeedbackType.Confirm, ManagerActionFeedback.PressScale)) {}
    val cancelPulse = rememberManagerActionHandler(managerActionFeedback(ManagerAction.Rejected)) {}
    val close: (Boolean) -> Unit = { accepted ->
        if (visible.value && active.value) {
            decision.value = accepted; visible.value = false
            if (accepted) confirmPulse() else cancelPulse()
        }
    }
    LaunchedEffect(active.value) {
        if (!active.value) { decision.value = false; visible.value = false }
    }
    OverlayDialog(show = visible.value,
        modifier = Modifier.semantics { dialog(); paneTitle = title },
        title = title, summary = message, onDismissRequest = { close(false) }, onDismissFinished = {
            val accepted = decision.value; decision.value = null
            if (accepted != null) { if (accepted && active.value) confirm.value() else cancel.value() }
        }) {
        Row {
            TextButton("取消", { close(false) }, Modifier.weight(1f), minHeight = 48.dp,
                enabled = visible.value && active.value)
            Spacer(Modifier.width(20.dp))
            TextButton(confirmLabel, { close(true) }, Modifier.weight(1f), minHeight = 48.dp,
                enabled = visible.value && active.value,
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}
