package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun MessagePageScaffold(onNew: () -> Unit, showNew: Boolean, searching: Boolean, query: String,
    onSearch: () -> Unit, onQuery: (String) -> Unit, onCloseSearch: () -> Unit,
    owner: com.vowifi.sw.manager.data.SimOwnerId? = null, onEntriesObscuredChange: (Boolean) -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    listModifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit) {
    val palette = phoneColors()
    val bottom = LocalRootBottomContentInset.current.value
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
            CommunicationSearchHeader("短信", "搜索短信", searching, query, onSearch, onQuery, onCloseSearch, state) {
                searchFocused = it
                publishObscured(active && (it || imeVisible))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().then(listModifier).managerVerticalScrollFeedback(), state = state, overscrollEffect = null,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom + if (showNew && !obscured) 108.dp else 24.dp), content = content)
        }
        CommunicationEntryVisibility(showNew && !obscured,
            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = bottom + 24.dp),
            retainOnInactiveRoute = true) { hitEnabled ->
            PhoneButton("新建短信", onNew, icon = CommunicationCompose, prominent = true,
                targetSize = 64.dp, roundedSquare = true, showLabel = false, hitEnabled = hitEnabled)
        }
    }
}
