package com.vowifi.sw.manager.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.flow.collectLatest

@Composable internal fun CommunicationSearchHeader(title: String, placeholder: String, searching: Boolean, query: String,
    onSearch: () -> Unit, onQuery: (String) -> Unit, onClose: () -> Unit, listState: LazyListState? = null,
    onFocusChanged: (Boolean) -> Unit = {}) {
    val active = LocalAppRouteActive.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closing = remember(searching) { mutableStateOf(false) }
    val close = {
        if (active && searching && !closing.value) {
            closing.value = true; focus.clearFocus(); keyboard?.hide()
            if (query.isNotEmpty()) onQuery("")
            onClose()
        }
    }
    val editable by rememberUpdatedState(active && searching && !closing.value)
    val updateQuery by rememberUpdatedState(onQuery)
    val publishFocus by rememberUpdatedState(onFocusChanged)
    val open = { if (active) onSearch() }
    DisposableEffect(active, searching) {
        if (!active || !searching) publishFocus(false)
        onDispose { publishFocus(false) }
    }
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = active && searching, onBackCompleted = close)
    }
    Column(Modifier.fillMaxWidth().communicationScrollDivider(listState)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 32.sp, lineHeight = 40.sp,
                fontWeight = FontWeight.Bold, color = phoneColors().text)
            CommunicationIconAction(CommunicationSearch, "搜索$title", if (searching) close else open, enabled = active)
        }
        AnimatedVisibility(searching,
            enter = expandVertically(tween(MotionTokens.DurBase, easing = MotionTokens.EaseStandard)) + fadeIn(tween(MotionTokens.DurBase)),
            exit = shrinkVertically(tween(MotionTokens.DurFast, easing = MotionTokens.EaseExit)) + fadeOut(tween(MotionTokens.DurFast))) {
            val requester = remember { FocusRequester() }; val inputReady = remember { mutableStateOf(false) }
            val focusReady = editable && inputReady.value && !transition.isRunning &&
                transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
            LaunchedEffect(focusReady) {
                if (focusReady && editable && !closing.value && requester.requestFocus()) keyboard?.show()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CommunicationInput(query, { if (editable) updateQuery(it) }, placeholder,
                    Modifier.weight(1f).focusRequester(requester).onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        inputReady.value = coordinates.size.width > 0 && coordinates.size.height > 0 &&
                            bounds.width >= coordinates.size.width - .5f && bounds.height >= coordinates.size.height - .5f
                    }.onFocusChanged { publishFocus(editable && it.isFocused) }.clearFocusOnInactiveRouteAndImeHide(),
                    enabled = editable, singleLine = true, leading = CommunicationSearch)
                CommunicationTextAction("取消", close, enabled = editable)
            }
        }
    }
}

/** The animation is observed only while drawing, so neither list nor header relayouts per frame. */
@Composable
private fun Modifier.communicationScrollDivider(state: LazyListState?): Modifier {
    val alpha = remember(state) { Animatable(if (state?.canScrollBackward == true) 1f else 0f) }
    val color = LocalSoftVoWiFiColors.current.separator
    LaunchedEffect(state) {
        snapshotFlow { state?.canScrollBackward == true }.collectLatest { scrolled ->
            alpha.animateTo(if (scrolled) 1f else 0f,
                tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard))
        }
    }
    return drawWithContent {
        drawContent()
        val thickness = 1.dp.toPx()
        drawRect(color, Offset(0f, size.height - thickness), Size(size.width, thickness), alpha = alpha.value)
    }
}
