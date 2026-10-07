package com.vowifi.sw.manager.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun MessageVisibilityBinder(
    handle: ConversationHandle,
    list: LazyListState,
    onVisible: (ConversationHandle?, List<String>) -> Unit,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = LocalAppRouteActive.current && lifecycle == Lifecycle.State.RESUMED
    DisposableEffect(visible, handle) {
        if (visible) onVisible(handle, emptyList())
        onDispose { if (visible) onVisible(null, emptyList()) }
    }
    LaunchedEffect(visible, handle, list) {
        if (!visible) return@LaunchedEffect
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                (item.key as? String)?.let { key -> when {
                    key.startsWith("incoming-") -> key.removePrefix("incoming-")
                    key.startsWith("outgoing-") -> key.removePrefix("outgoing-")
                    else -> null
                } }
            }
        }.distinctUntilChanged().collect { ids -> onVisible(handle, ids) }
    }
}
