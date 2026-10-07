package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

@Composable internal fun MessagePageScaffold(onNew: () -> Unit, showNew: Boolean, searching: Boolean, query: String,
    onSearch: () -> Unit, onQuery: (String) -> Unit, onCloseSearch: () -> Unit, content: LazyListScope.() -> Unit) {
    val palette = phoneColors()
    val bottom = LocalRootBottomContentInset.current.value
    val state = rememberLazyListState()
    val scroll = rememberSmsScrollFeedback(state.canScrollBackward || state.canScrollForward)
    Box(Modifier.fillMaxSize().background(palette.canvas).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            CommunicationSearchHeader("短信", "搜索短信", searching, query, onSearch, onQuery, onCloseSearch)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().then(scroll.modifier), state = state, overscrollEffect = scroll.effect,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom + if (showNew) 108.dp else 24.dp), content = content)
        }
        if (showNew) PhoneButton("新建短信", onNew, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = bottom + 24.dp),
            icon = CommunicationCompose, prominent = true, targetSize = 64.dp, roundedSquare = true, showLabel = false)
    }
}
