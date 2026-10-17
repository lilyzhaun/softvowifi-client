package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PhoneHistoryScaffold(searching: Boolean, query: String,
    onSearch: () -> Unit, onQuery: (String) -> Unit, onCloseSearch: () -> Unit,
    showDial: Boolean, onDial: () -> Unit, owner: com.vowifi.sw.manager.data.SimOwnerId? = null,
    onEntriesObscuredChange: (Boolean) -> Unit = {},
    historyChange: com.vowifi.sw.manager.model.HistoryListChange<com.vowifi.sw.manager.model.CallHistoryRecordId>? = null,
    content: LazyListScope.() -> Unit) {
    val palette = phoneColors()
    val bottom = LocalRootBottomContentInset.current.value
    val state = rememberLazyListState()
    val active = LocalAppRouteActive.current
    var searchFocused by remember { mutableStateOf(false) }
    val imeVisible = currentImeVisible()
    val obscured = searchFocused || imeVisible
    val publishObscured by rememberUpdatedState(onEntriesObscuredChange)
    SideEffect { publishObscured(active && obscured) }
    DisposableEffect(Unit) { onDispose { publishObscured(false) } }
    ResetManagerScrollOnConditionChange(state, query)
    OnConfirmedManagerOwnerChange(owner) { state.scrollToItem(0) }
    RegisterRootScrollToTop(state)
    Box(Modifier.fillMaxSize().background(palette.canvas).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            CommunicationSearchHeader("通话记录", "搜索号码", searching, query, onSearch, onQuery, onCloseSearch, state) {
                searchFocused = it
                publishObscured(active && (it || imeVisible))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().managerVerticalScrollFeedback()
                .then(if (searching) Modifier else Modifier.callsHistoryReplacementFade(historyChange, owner to query)),
                state = state, overscrollEffect = null,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 0.dp,
                    bottom = bottom + if (showDial && !obscured) 108.dp else 24.dp), content = content)
        }
        CommunicationEntryVisibility(showDial && !obscured,
            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = bottom + 24.dp),
            retainOnInactiveRoute = true) { hitEnabled ->
            PhoneButton("拨号盘", onDial, icon = CallPhoneIcons.Keypad, prominent = true,
                targetSize = 64.dp, roundedSquare = true, showLabel = false, hitEnabled = hitEnabled)
        }
    }
}
