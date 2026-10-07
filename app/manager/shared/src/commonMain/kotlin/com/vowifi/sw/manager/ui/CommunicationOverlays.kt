package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.DialogLayout

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
    onCancel: () -> Unit, onConfirm: () -> Unit, destructive: Boolean = true) {
    val colors = LocalSoftVoWiFiColors.current
    val visible = remember { mutableStateOf(true) }
    val duration = smsInnerMotionMillis()
    // The public host owns modal lifecycle and focus; only the alert content/layout is authored.
    DialogLayout(visible, enableAutoLargeScreen = false, enterTransition = fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = .96f),
        exitTransition = fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = .96f)) {
        val back = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(back, isBackEnabled = true, onBackCompleted = onCancel)
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().pointerInput(onCancel) {
            detectTapGestures { onCancel() }
        }.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 300.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(communicationColors().list).semantics { dialog(); paneTitle = title }
            .pointerInput(Unit) { detectTapGestures { } }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.fillMaxWidth(), fontSize = 17.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(message, Modifier.fillMaxWidth(), color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center)
            }
            HorizontalDivider(color = colors.separator, thickness = .5.dp)
            Row(Modifier.fillMaxWidth()) {
                CommunicationTextAction("取消", onCancel, Modifier.weight(1f))
                CommunicationTextAction(confirmLabel, onConfirm, Modifier.weight(1f), destructive = destructive, weight = FontWeight.SemiBold)
            }
        }
        }
    }
}
