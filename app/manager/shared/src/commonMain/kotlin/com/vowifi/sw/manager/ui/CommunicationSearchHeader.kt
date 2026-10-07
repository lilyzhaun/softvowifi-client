package com.vowifi.sw.manager.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable internal fun CommunicationSearchHeader(title: String, placeholder: String, searching: Boolean, query: String,
    onSearch: () -> Unit, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val active = LocalAppRouteActive.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val close = { focus.clearFocus(); keyboard?.hide(); onClose() }
    val editable by rememberUpdatedState(active && searching)
    val updateQuery by rememberUpdatedState(onQuery)
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = active && searching, onBackCompleted = close)
    }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 32.sp, lineHeight = 40.sp,
            fontWeight = FontWeight.Bold, color = phoneColors().text)
        CommunicationIconAction(CommunicationSearch, "搜索$title", if (searching) close else onSearch, enabled = active)
    }
    AnimatedVisibility(searching, enter = expandVertically(tween(200)) + fadeIn(tween(140)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(100))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CommunicationInput(query, { if (editable) updateQuery(it) }, placeholder, Modifier.weight(1f).clearFocusOnInactiveRouteAndImeHide(),
                enabled = active && searching, singleLine = true, leading = CommunicationSearch)
            CommunicationTextAction("取消", close, enabled = active)
        }
    }
}
