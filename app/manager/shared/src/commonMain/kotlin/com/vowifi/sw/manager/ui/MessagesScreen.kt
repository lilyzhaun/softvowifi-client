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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import com.vowifi.sw.manager.data.MessageConversationAction
import com.vowifi.sw.manager.data.MessageConversationActionResult
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Refresh

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
    selectionNotice: String? = null,
    hasRegisteredEngine: Boolean = false,
    onConversationAction: suspend (ConversationHandle, com.vowifi.sw.manager.data.MessageConversationAction) -> com.vowifi.sw.manager.data.MessageConversationActionResult = { _, _ -> com.vowifi.sw.manager.data.MessageConversationActionResult.FAILED },
) = PhoneStyle {
    val displayedState = rememberMessageRouteFrame(state)
    var pickerOpen by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<MessageRemarkEditor?>(null) }
    var searchQuery by rememberMessageSearchQuery()
    var searching by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    val page = projectMessageConversations(messageProjectionOwner(displayedState), displayedState)
    var deletion by remember { mutableStateOf<ConversationHandle?>(null) }
    var changing by remember { mutableStateOf(false) }
    var operationNotice by remember { mutableStateOf<String?>(null) }
    val active by rememberUpdatedState(LocalAppRouteActive.current)
    val currentAction by rememberUpdatedState(onConversationAction)
    val scope = rememberCoroutineScope()
    LaunchedEffect(active, messageProjectionOwner(displayedState)) { deletion = null; operationNotice = null }
    val applyAction: (ConversationHandle, MessageConversationAction) -> Unit = { handle, action ->
        if (active && !changing) {
            changing = true
            scope.launch {
                try {
                    operationNotice = when (currentAction(handle, action)) {
                        MessageConversationActionResult.APPLIED -> null
                        MessageConversationActionResult.MISSING -> "该会话已不存在，请刷新列表。"
                        MessageConversationActionResult.FAILED -> "无法确认操作结果，请刷新列表后确认。"
                    }
                } finally { changing = false }
            }
        }
    }
    val filteredRows = if (displayedState is MessageUiState.Inbox) page.rows else filterMessageConversationRows(page.rows, searchQuery)
    val inbox = displayedState as? MessageUiState.Inbox
    val initialLoading = inbox?.let { it.page.items.isEmpty() && it.page.loading } == true
    val initialFailure = inbox?.let { it.page.items.isEmpty() && it.page.failed && !it.page.loading } == true
    val completedSearch = (inbox?.page?.query ?: searchQuery).isNotEmpty()
    val knownEmptyHistory = displayedState == MessageUiState.Empty ||
        displayedState is MessageUiState.Unavailable && historyChoices.storageAvailable && historyChoices.lines.isEmpty()
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    androidx.compose.runtime.LaunchedEffect(searchQuery) { onSearchHistory(searchQuery) }
    MessagePageScaffold(onNewMessage, hasRegisteredEngine, searching, searchQuery,
        { searching = true }, { searchQuery = boundedMessageSearchQuery(it) }, { searching = false; searchQuery = "" }) {
        if (historyChoices.lines.isNotEmpty()) {
            item(key = "message-line-entry") { MessageLineEntry(historyChoices) { pickerOpen = true } }
        }
        operationNotice?.let { notice -> item(key = "conversation-action-notice") { CommunicationNotice(notice) } }
        selectionNotice?.let { notice ->
            item(key = "message-draft-recovery") {
                androidx.compose.foundation.layout.Column {
                    CommunicationNotice(notice)
                     CommunicationTextAction("处理未绑定草稿", onNewMessage)
                }
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
        messageFixtureDisclosure(AppRoute.Messages, displayedState)?.let {
            item(key = "fixture-disclosure") { MessageFixtureDisclosureBand(it) }
        }
        val compactNotice = messageRootCompactNotice(displayedState)
        compactNotice?.let { notice ->
            item(key = "messages-compact-notice") { MessageRootReadOnlyNotice(notice) }
        }
        if (initialLoading) item(key = "messages-initial-loading") {
            CommunicationListState(StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE,
                "正在读取消息", "完成后将显示短信会话。", loading = true))
        } else if (initialFailure) item(key = "messages-initial-failure") {
            CommunicationListState(StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR,
                "消息读取失败", "无法读取短信会话，请稍后重试。")) {
                CommunicationTextAction("加载失败，重试加载", onLoadMore)
            }
        } else if (compactNotice == null && !knownEmptyHistory) {
            messageStateSpec(displayedState)?.let { panel ->
                item(key = "messages-state") {
                    if (displayedState is MessageUiState.Available) CommunicationNotice(panel.title, panel.description)
                    else CommunicationListState(panel) {
                        if (onRefreshHistory != null && displayedState != MessageUiState.Loading) {
                            CommunicationTextAction("刷新短信历史", onRefreshHistory)
                        }
                    }
                }
            }
        }
        if ((displayedState is MessageUiState.Available || knownEmptyHistory) &&
            (inbox == null || !inbox.page.loading && !inbox.page.failed)) {
            if (filteredRows.isEmpty() && completedSearch) {
                item(key = "messages-search-empty") { CommunicationListState(messageSearchEmptySpec().copy(icon = CommunicationSearch)) }
            } else if (page.rows.isEmpty()) {
                item(key = "messages-empty") { CommunicationListState(StatePanelSpec(MiuixIcons.Messages,
                    StatePanelTone.NEUTRAL, "暂无会话", "发送或收到短信后，会话会显示在这里。")) {
                    if (displayedState is MessageUiState.Unavailable && onRefreshHistory != null) {
                        CommunicationTextAction("刷新短信历史", onRefreshHistory)
                    }
                } }
            }
        }
        items(filteredRows, key = { row -> row.handle.value }) { row ->
            MessageConversationMenu(
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(messageContentAnimationContract().durationMillis),
                    placementSpec = null,
                    fadeOutSpec = tween(messageContentAnimationContract().durationMillis),
                ),
                spec = ConversationRowPresentation(
                    peerDisplay = row.peerDisplay,
                    avatarText = row.avatarText,
                    preview = row.latestPreview,
                    timeText = formatCommunicationTime(row.timestamp.epochMs, now) { messageDateSpec(it).label },
                    statusText = row.outgoingStatus,
                ),
                onOpen = { onOpenThread(row.handle) },
                onAction = { action ->
                    if (action == MessageConversationAction.DELETE) deletion = row.handle
                    else applyAction(row.handle, action)
                },
                enabled = !changing,
                unreadCount = row.unreadCount,
            )
        }
        if (displayedState is MessageUiState.Inbox && filteredRows.isNotEmpty()) item(key = "history-page-action") {
            MessagePageAction(displayedState.page.loading, displayedState.page.failed, displayedState.page.next != null, "加载更多会话",
                if (displayedState.page.query.isEmpty()) "已显示全部历史" else "已显示全部匹配会话", onLoadMore)
        }
    }
    if (pickerOpen) MessageLinePicker(historyChoices, { pickerOpen = false }, {
        pickerOpen = false
        onSelectHistoryOwner(it)
    }, {
        pickerOpen = false
        editor = MessageRemarkEditor(it.owner, it.label?.remark.orEmpty(), it.label?.ordinal, it.label?.defaultName ?: it.simLabel)
    }, onRefreshHistory)
    editor?.let { MessageRemarkDialog(it, { editor = null }, onSaveOwnerRemark) }
    deletion?.takeIf { active }?.let { handle ->
        CommunicationConfirmation("删除会话？", "将删除本机保存的该会话全部短信，无法撤销。", "删除", { deletion = null }, {
            if (deletion == handle) { deletion = null; applyAction(handle, MessageConversationAction.DELETE) }
        })
    }
}

@Composable
internal fun MessageFixtureDisclosureBand(text: String = FIXTURE_DISCLOSURE_TEXT) {
    CommunicationNotice(text)
}

@Composable
internal fun rememberMessageSearchQuery(): MutableState<String> = rememberSaveable { mutableStateOf("") }
