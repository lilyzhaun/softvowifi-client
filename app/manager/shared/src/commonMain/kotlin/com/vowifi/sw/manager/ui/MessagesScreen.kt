package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Messages

internal const val FIXTURE_DISCLOSURE_TEXT = "桌面夹具数据，不代表 Android 后端可用。"

@Composable
fun MessagesScreen(
    state: MessageUiState,
    onOpenThread: (ConversationHandle) -> Unit,
    onNewMessage: () -> Unit,
    notificationsEnabled: Boolean? = null,
    onNotificationSettings: () -> Unit = {},
    historyChoices: com.vowifi.sw.manager.model.MessageHistoryChoices = com.vowifi.sw.manager.model.MessageHistoryChoices(),
    onSelectHistoryOwner: (com.vowifi.sw.manager.data.SimOwnerId) -> Unit = {},
    onRefreshHistory: (() -> Unit)? = null,
    onSaveOwnerRemark: suspend (com.vowifi.sw.manager.data.SimOwnerId, String) -> com.vowifi.sw.manager.data.MessageRemarkSaveResult = { _, _ -> com.vowifi.sw.manager.data.MessageRemarkSaveResult.Failed },
    onSearchHistory: (String) -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<MessageRemarkEditor?>(null) }
    var searchQuery by rememberMessageSearchQuery()
    val page = projectMessageConversations(messageProjectionOwner(state), state)
    val filteredRows = if (state is MessageUiState.Inbox) page.rows else filterMessageConversationRows(page.rows, searchQuery)
    androidx.compose.runtime.LaunchedEffect(searchQuery) { onSearchHistory(searchQuery) }
    AppPageScaffold(
        title = appRootTitle(AppRoute.Messages),
        actions = {
            ManagerIconAction(MiuixIcons.Messages, "新建短信", ManagerAction.Navigate, onNewMessage)
        },
    ) {
        if (state is MessageUiState.Available || historyChoices.lines.isNotEmpty()) {
            item(key = "messages-search") { MessageSearchField(query = searchQuery, onQueryChange = { searchQuery = it }) }
        }
        if (historyChoices.lines.isNotEmpty()) {
            item(key = "message-line-entry") { MessageLineEntry(historyChoices) { pickerOpen = true } }
        }
        if (onRefreshHistory != null && (state is MessageUiState.Failure || state is MessageUiState.Unavailable || state is MessageUiState.PermissionDenied)) {
            item(key = "refresh-history") {
                ManagerTextAction(label = "刷新短信历史", action = ManagerAction.Navigate, onClick = onRefreshHistory)
            }
        }
        if (notificationsEnabled == false) {
            item(key = "notification-permission") {
                val feedback = managerActionFeedback(ManagerAction.Navigate)
                val handler = rememberManagerActionHandler(feedback, onNotificationSettings)
                val interaction = remember { MutableInteractionSource() }
                Text(
                    "短信通知未开启，点此打开系统设置。短信仍会保存。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .managerPressScale(feedback, interaction)
                        .clickable(interactionSource = interaction, indication = null, onClick = handler)
                        .padding(vertical = 12.dp),
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
        messageFixtureDisclosure(AppRoute.Messages, state)?.let {
            item(key = "fixture-disclosure") { MessageFixtureDisclosureBand(it) }
        }
        val compactNotice = messageRootCompactNotice(state)
        compactNotice?.let { notice ->
            item(key = "messages-compact-notice") { MessageRootReadOnlyNotice(notice) }
        }
        if (compactNotice == null) {
            messageStateSpec(state)?.let { panel ->
                item(key = "messages-state") { StatePanel(panel) }
            }
        }
        if ((state is MessageUiState.Available || historyChoices.storageAvailable && historyChoices.lines.isEmpty()) &&
            (state !is MessageUiState.Inbox || !state.page.loading && !state.page.failed)) {
            if (page.rows.isEmpty()) {
                item(key = "messages-empty") { MessageEmptyPanel() }
            } else if (filteredRows.isEmpty()) {
                item(key = "messages-search-empty") { StatePanel(messageSearchEmptySpec()) }
            }
        }
        items(filteredRows, key = { row -> row.handle.value }) { row ->
            ConversationRow(
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(messageContentAnimationContract().durationMillis),
                    placementSpec = null,
                    fadeOutSpec = tween(messageContentAnimationContract().durationMillis),
                ),
                spec = ConversationRowPresentation(
                    peerDisplay = row.peerDisplay,
                    avatarText = row.avatarText,
                    preview = row.latestPreview,
                    timeText = messageTimestampLabel(row.timestamp.epochMs),
                    statusText = row.outgoingStatus,
                ),
                onClick = { onOpenThread(row.handle) },
                unreadCount = row.unreadCount,
            )
        }
        if (state is MessageUiState.Inbox) item(key = "history-page-action") {
            MessagePageAction(state.page.loading, state.page.failed, state.page.next != null, "加载更多会话",
                if (state.page.query.isEmpty()) "已显示全部历史" else "已显示全部匹配会话", onLoadMore)
        }
    }
    if (pickerOpen) MessageLinePicker(historyChoices, { pickerOpen = false }, {
        pickerOpen = false
        onSelectHistoryOwner(it)
    }, {
        pickerOpen = false
        editor = MessageRemarkEditor(it.owner, it.label?.remark.orEmpty(), it.label?.ordinal)
    }, onRefreshHistory)
    editor?.let { MessageRemarkDialog(it, { editor = null }, onSaveOwnerRemark) }
}

@Composable
internal fun MessageFixtureDisclosureBand(text: String = FIXTURE_DISCLOSURE_TEXT) {
    CommunicationNotice(text)
}

@Composable
internal fun rememberMessageSearchQuery(): MutableState<String> = rememberSaveable { mutableStateOf("") }

@Composable
private fun MessageEmptyPanel() {
    CommunicationNotice("暂无会话", "发送或收到短信后，会话会显示在这里。")
}
