package com.vowifi.sw.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.vowifi.sw.manager.data.MessageConversationAction
import com.vowifi.sw.manager.data.MessageConversationActionResult
import com.vowifi.sw.manager.data.MessageSummaryCursor
import com.vowifi.sw.manager.data.SimOwnerId
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.HistoryChangeReason
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
    onEntriesObscuredChange: (Boolean) -> Unit = {},
    onConfirmHistoryOwner: (suspend (SimOwnerId) -> Boolean)? = null,
    onConversationAction: suspend (ConversationHandle, com.vowifi.sw.manager.data.MessageConversationAction) -> com.vowifi.sw.manager.data.MessageConversationActionResult = { _, _ -> com.vowifi.sw.manager.data.MessageConversationActionResult.FAILED },
) = PhoneStyle {
    val displayedState = rememberMessageRouteFrame(state)
    var pickerOpen by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<MessageRemarkEditor?>(null) }
    var searchQuery by rememberMessageSearchQuery()
    var searching by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    val page = projectMessageConversations(messageProjectionOwner(displayedState), displayedState)
    val inbox = displayedState as? MessageUiState.Inbox
    val selection = Triple(messageProjectionOwner(displayedState), searchQuery to (inbox?.page?.query ?: searchQuery), inbox?.page?.change?.scope)
    val currentSelection by rememberUpdatedState(selection)
    val filteredRows = if (displayedState is MessageUiState.Inbox) page.rows else filterMessageConversationRows(page.rows, searchQuery)
    val currentRows by rememberUpdatedState(filteredRows)
    val active by rememberUpdatedState(LocalAppRouteActive.current)
    var deletion by remember(selection, active) { mutableStateOf<ConversationHandle?>(null) }
    var pending by remember(selection, active) { mutableStateOf<ConversationHandle?>(null) }
    var retainedDeletion by remember(selection, active) { mutableStateOf<Pair<Int, MessageConversationRow>?>(null) }
    var appliedDeletion by remember(selection, active) { mutableStateOf<ConversationHandle?>(null) }
    var operationNotice by remember(selection, active) { mutableStateOf<String?>(null) }
    val currentAction by rememberUpdatedState(onConversationAction)
    val scope = rememberCoroutineScope()
    LaunchedEffect(active, messageProjectionOwner(displayedState)) { deletion = null; operationNotice = null }
    val applyAction: (ConversationHandle, MessageConversationAction) -> Unit = { handle, action ->
        if (active && pending == null) {
            operationNotice = null
            appliedDeletion = null
            pending = handle
            if (action == MessageConversationAction.DELETE) {
                val index = filteredRows.indexOfFirst { it.handle == handle }
                retainedDeletion = filteredRows.getOrNull(index)?.let { index to it }
            }
            scope.launch {
                try {
                    val result = currentAction(handle, action)
                    // 源IO继续结算；旧owner/query的UI结果不挂到新的选择上。
                    if (!active || currentSelection != selection) return@launch
                    operationNotice = when (result) {
                        MessageConversationActionResult.APPLIED -> {
                            if (action == MessageConversationAction.DELETE) appliedDeletion = handle
                            null
                        }
                        MessageConversationActionResult.MISSING -> "该会话已不存在，请刷新列表。"
                        MessageConversationActionResult.FAILED -> "无法确认操作结果，请刷新列表后确认。"
                    }
                } finally {
                    if (appliedDeletion != handle) { pending = null; retainedDeletion = null }
                }
            }
        }
    }
    val rows = retainedDeletion?.let { (index, row) ->
        if (filteredRows.any { it.handle == row.handle }) filteredRows else
            filteredRows.toMutableList().apply { add(index.coerceAtMost(size), row) }
    } ?: filteredRows
    val readbackReady = displayedState is MessageUiState.Ready || inbox?.page?.let { !it.loading && !it.failed } == true
    val removing = appliedDeletion?.takeIf { handle -> readbackReady && currentRows.none { it.handle == handle } }
    val listState = rememberLazyListState()
    val currentInbox by rememberUpdatedState(inbox)
    val loadMore by rememberUpdatedState(onLoadMore)
    val inserted = rememberMessageInsertedIds(inbox?.page?.change, active && !searching)
    // R6：观察的就是scaffold唯一列表；只发当前cursor一次，失败保留原手动重试。
    LaunchedEffect(listState, inbox?.ownerId, inbox?.page?.query, inbox?.page?.change?.scope) {
        var requested: MessageSummaryCursor? = null
        snapshotFlow {
            val current = currentInbox?.page
            val layout = listState.layoutInfo
            val nearEnd = current?.items?.takeLast(3)?.any { summary ->
                layout.visibleItemsInfo.any { it.key == summary.identity.handle.value &&
                    it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
            } == true
            if (active && current != null && current.items.isNotEmpty() && !current.loading && !current.failed && nearEnd)
                current.next else null
        }.collect { cursor ->
            if (cursor != null && cursor != requested) { requested = cursor; loadMore() }
        }
    }
    val initialLoading = inbox?.let { it.page.items.isEmpty() && it.page.loading } == true
    val initialFailure = inbox?.let { it.page.items.isEmpty() && it.page.failed && !it.page.loading } == true
    val completedSearch = (inbox?.page?.query ?: searchQuery).isNotEmpty()
    val knownEmptyHistory = displayedState == MessageUiState.Empty ||
        displayedState is MessageUiState.Unavailable && historyChoices.storageAvailable && historyChoices.lines.isEmpty()
    val compactNotice = messageRootCompactNotice(displayedState)
    val statePanel = if (!initialLoading && !initialFailure && compactNotice == null && !knownEmptyHistory)
        messageStateSpec(displayedState) else null
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    androidx.compose.runtime.LaunchedEffect(searchQuery) { onSearchHistory(searchQuery) }
    MessagePageScaffold(onNewMessage, hasRegisteredEngine, searching, searchQuery,
        { searching = true }, { searchQuery = boundedMessageSearchQuery(it) }, { searching = false; searchQuery = "" },
        owner = messageProjectionOwner(displayedState), onEntriesObscuredChange = onEntriesObscuredChange, state = listState,
        // Filtering an already-visible root is not a new page entrance on every keystroke.
        listModifier = if (searching) Modifier else Modifier.messageReplacementFade(inbox?.page?.change, selection)) {
        if (historyChoices.lines.isNotEmpty()) {
            item(key = "message-line-entry") { MessageLineEntry(historyChoices) { pickerOpen = true } }
        }
        communicationNoticeItem("conversation-action-notice", operationNotice?.let { CommunicationNoticeSpec(it) })
        communicationNoticeItem("message-draft-recovery", selectionNotice?.let { CommunicationNoticeSpec(it) }) { notice, interactive ->
            androidx.compose.foundation.layout.Column {
                CommunicationNotice(notice.title, notice.summary)
                if (interactive) CommunicationTextAction("处理未绑定草稿", onNewMessage)
                else androidx.compose.foundation.layout.Box(Modifier.heightIn(min = 48.dp).padding(12.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("处理未绑定草稿", color = communicationColors().accent, fontSize = 17.sp, lineHeight = 24.sp)
                }
            }
        }
        communicationNoticeItem("notification-permission", if (notificationsEnabled == false)
            CommunicationNoticeSpec("短信通知未开启，点此打开系统设置。短信仍会保存。") else null) { notice, interactive ->
            val feedback = managerActionFeedback(ManagerAction.Navigate, interactive)
            val handler = rememberManagerActionHandler(feedback, onNotificationSettings)
            val interaction = remember { MutableInteractionSource() }
            val input = if (interactive) Modifier.managerPressInputGate(true)
                .clickable(interactionSource = interaction, indication = null, onClick = handler) else Modifier
            Text(notice.title, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .managerKeyboardFocusRing(interaction, interactive, androidx.compose.ui.graphics.RectangleShape, communicationColors().accent)
                .communicationContentPress(feedback, interaction).then(input)
                .padding(vertical = 12.dp),
                fontSize = 14.sp, lineHeight = 20.sp)
        }
        messageFixtureDisclosure(AppRoute.Messages, displayedState)?.let {
            item(key = "fixture-disclosure") { MessageFixtureDisclosureBand(it) }
        }
        communicationNoticeItem("messages-compact-notice", compactNotice?.let { CommunicationNoticeSpec(it) }) { notice, _ ->
            MessageRootReadOnlyNotice(notice.title)
        }
        communicationNoticeItem("messages-state-notice", statePanel?.takeIf { displayedState is MessageUiState.Available }
            ?.let { CommunicationNoticeSpec(it.title, it.description) })
        if (initialLoading) item(key = "messages-initial-loading") {
            CommunicationListState(StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE,
                "正在读取消息", "完成后将显示短信会话。", loading = true))
        } else if (initialFailure) item(key = "messages-initial-failure") {
            CommunicationListState(StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR,
                "消息读取失败", "无法读取短信会话，请稍后重试。")) {
                CommunicationTextAction("加载失败，重试加载", onLoadMore)
            }
        } else if (displayedState !is MessageUiState.Available) {
            statePanel?.let { panel ->
                item(key = "messages-state") {
                    CommunicationListState(panel) {
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
        items(rows, key = { row -> row.handle.value }) { row ->
            val enter = remember(row.handle, inbox?.page?.change?.scope, inbox?.page?.query, searching) { row.handle in inserted }
            MessageConversationMenu(
                modifier = Modifier.messageHistoryItem(inbox?.page?.change, selection, enter,
                    place = !searching && messageContentAnimationContract(inbox?.page?.change?.reason).animatePlacement &&
                        (inbox?.page?.change?.reason != HistoryChangeReason.USER_DELETE || retainedDeletion == null))
                    .then(when {
                        !active || row.handle == removing -> Modifier.clearAndSetSemantics { }
                        row.handle == pending -> Modifier.semantics(mergeDescendants = true) {
                            disabled()
                            stateDescription = if (retainedDeletion?.second?.handle == row.handle) "正在删除" else "正在标为未读"
                        }
                        else -> Modifier
                    }),
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
                enabled = row.handle != pending && row.handle != removing,
                unreadCount = row.unreadCount,
                statusFailed = row.statusFailed,
                pending = row.handle == pending,
                removing = row.handle == removing,
                onRemoved = { retainedDeletion = null; pending = null; appliedDeletion = null },
                rootCustomActionsBlocked = deletion != null || pickerOpen || editor != null,
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
    }, onRefreshHistory, onConfirm = onConfirmHistoryOwner)
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
