package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info

internal data class MessageListChromeSpec(
    val searchCapsule: Boolean = true,
    val insideSingleLazyColumn: Boolean = true,
    val expandedResultsSurface: Boolean = false,
    val tabularTime: Boolean = true,
    val perRowCard: Boolean = false,
)

internal fun messageListChromeContract(): MessageListChromeSpec = MessageListChromeSpec()

internal fun boundedMessageSearchQuery(raw: String): String {
    val filtered = raw.filterNot(Char::isISOControl)
    return if (filtered.length <= 80) filtered else filtered.take(80)
}

internal fun filterMessageConversationRows(
    rows: List<MessageConversationRow>,
    query: String,
): List<MessageConversationRow> {
    val bounded = boundedMessageSearchQuery(query)
    val trimmed = bounded.trim()
    if (trimmed.isEmpty()) return rows
    return rows.filter { row ->
        row.peerDisplay.contains(trimmed, ignoreCase = true) ||
            row.latestPreview.contains(trimmed, ignoreCase = true)
    }
}

internal fun messageSearchEmptySpec(): StatePanelSpec = StatePanelSpec(
    icon = MiuixIcons.Info,
    tone = StatePanelTone.NEUTRAL,
    title = "未找到匹配会话",
    description = "尝试使用不同的号码或短信内容关键词搜索。",
)

@Composable
internal fun MessageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    InputField(
        query = query,
        onQueryChange = { onQueryChange(boundedMessageSearchQuery(it)) },
        onSearch = { focusManager.clearFocus() },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
            .clearFocusOnInactiveRouteAndImeHide()
            .onFocusChanged { expanded = it.isFocused }
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        label = "搜索",
    )
}
