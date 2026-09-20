package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.layout.heightIn
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info

@Composable
internal fun MessageThreadScreen(
    handle: ConversationHandle,
    state: MessageUiState,
    onSend: (String, String) -> Unit,
    onVisible: (ConversationHandle?, List<String>) -> Unit = { _, _ -> },
    onLoadOlder: () -> Unit = {},
) {
    val thread = projectMessageThread(handle, messageProjectionOwner(state), state, ::messageDateSpec)
    if (thread == null) {
        MessageMissingThread(state, onLoadOlder)
        return
    }
    var localBody by rememberSaveable(handle.value) { mutableStateOf("") }
    val drafts = LocalThreadDrafts.current
    val body = drafts?.value?.get(handle.value) ?: localBody
    val updateBody: (String) -> Unit = { value ->
        localBody = value
        drafts?.let { it.value = it.value + (handle.value to value) }
    }
    val submission = rememberMessageDraftSubmission(state, thread.recipient?.value.orEmpty(), body, { updateBody("") }, onSend)
    val requestImeOwner = rememberMessageImeOwnerRequest()
    var selectedId by remember(handle) { mutableStateOf<String?>(null) }
    var copied by remember(handle) { mutableStateOf(false) }
    val loadedIds = thread.items.mapNotNull { item -> when (item) {
        is MessageThreadItem.IncomingBubble -> item.recordId
        is MessageThreadItem.OutgoingBubble -> item.recordId
        is MessageThreadItem.DateSeparator -> null
    } }
    val selectedRecord = (state as? MessageUiState.Ready)?.records?.firstOrNull { it.id == selectedId && it.id in loadedIds }
    LaunchedEffect(selectedId, selectedRecord) { if (selectedRecord == null) selectedId = null }
    LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }
    MessageThreadActions(selectedRecord, body, thread.compose.editingEnabled && !submission.pending,
        { selectedId = null }, { submission.draftEdited(); updateBody(it) }, { copied = true })
    CompositionLocalProvider(LocalSmsBubbleAction provides { id -> selectedId = id }) {
    Column(Modifier.fillMaxSize()) {
        MessageThreadTimeline(
            route = AppRoute.MessageThread(handle),
            state = state,
            thread = thread,
            modifier = Modifier.weight(1f),
            onVisible = onVisible,
            onLoadOlder = onLoadOlder,
        )
        if (copied) Text("已复制", modifier = Modifier.padding(horizontal = 16.dp), color = LocalSoftVoWiFiColors.current.textSecondary)
        MessageComposer(
            body = body,
            compose = thread.compose,
            imeOwner = messageRouteLayout(AppRoute.MessageThread(handle)).imeOwner,
            requestImeOwner = requestImeOwner,
            pending = submission.pending,
            canSend = thread.recipient != null && body.isNotBlank(),
            onBodyChange = { submission.draftEdited(); updateBody(boundedDraftBody(it)) },
            onSend = submission.submit,
        )
    }
    }
}

@Composable
private fun MessageThreadTimeline(
    route: AppRoute.MessageThread,
    state: MessageUiState,
    thread: MessageThreadPage,
    modifier: Modifier,
    onVisible: (ConversationHandle?, List<String>) -> Unit,
    onLoadOlder: () -> Unit,
) {
    val topInset = LocalSubpageTopContentInset.current
    val spacing = messageSubpageSpacing()
    val disclosure = messageFixtureDisclosure(route, state)
    val timelineState = rememberLazyListState()
    val searchState = rememberLazyListState()
    var searchOpen by rememberSaveable(thread.handle.value) { mutableStateOf(false) }
    var query by rememberSaveable(thread.handle.value) { mutableStateOf("") }
    val searching = searchOpen && query.isNotEmpty()
    val shownItems = remember(thread.items, query, searchOpen) { filterLoadedThread(thread.items, if (searchOpen) query else "") }
    val listState = if (searching) searchState else timelineState
    MessageVisibilityBinder(thread.handle, listState, onVisible)
    val window = (state as? MessageUiState.Ready)?.window
    val lastItemIndex = messageThreadLastItemIndex(shownItems.size, disclosure != null)?.plus(if (window != null) 1 else 0)
    var anchor by remember(thread.handle) { mutableStateOf<Pair<String, Int>?>(null) }
    val latestId = (state as? MessageUiState.Ready)?.records?.lastOrNull()?.id
    var previousLastIndex by remember(thread.handle) { mutableStateOf<Int?>(null) }
    var previousLatestId by remember(thread.handle) { mutableStateOf<String?>(null) }
    var hasNewMessages by remember(thread.handle) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val duration = smsInnerMotionMillis()
    LaunchedEffect(latestId) {
        if (searchOpen) {
            if (previousLatestId != null && latestId != previousLatestId) hasNewMessages = true
            previousLatestId = latestId
            return@LaunchedEffect
        }
        val latestIndex = lastItemIndex ?: return@LaunchedEffect
        val previousIndex = previousLastIndex
        val initial = previousLatestId == null
        val visible = snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.first { it != null } ?: return@LaunchedEffect
        if (shouldFollowNewMessages(initial, previousIndex ?: latestIndex, visible)) {
            listState.requestScrollToItem(latestIndex)
            hasNewMessages = false
        } else if (latestId != previousLatestId) {
            hasNewMessages = true
        }
        previousLastIndex = latestIndex
        previousLatestId = latestId
    }
    LaunchedEffect(thread.items.size, window?.loading) {
        val saved = anchor
        if (saved != null && window?.loading == false) {
            val index = shownItems.indexOfFirst { threadItemKey(it) == saved.first }
            if (index >= 0) listState.requestScrollToItem(index + (if (disclosure != null) 1 else 0) + 1, saved.second)
            anchor = null
        }
        if (latestId == previousLatestId) previousLastIndex = lastItemIndex
    }
    Column(modifier.fillMaxWidth().padding(top = topInset + 8.dp).clipToBounds()) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(if (thread.compose.mode == MessageComposeMode.READ_ONLY) "只读：可编辑草稿，连接后再发送。" else "当前会话线路",
            color = LocalSoftVoWiFiColors.current.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        MessageThreadSearch(searchOpen, query, shownItems.count { it !is MessageThreadItem.DateSeparator },
            onToggle = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, onQuery = { query = it })
        if (searchOpen) ManagerTextAction("回到最新", ManagerAction.Navigate, {
            query = ""; searchOpen = false
            val latest = messageThreadLastItemIndex(thread.items.size, disclosure != null)?.plus(if (window != null) 1 else 0)
            latest?.let { timelineState.requestScrollToItem(it) }
            hasNewMessages = false
        })
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val inset = if (maxWidth >= 600.dp) 24.dp else 16.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().widthIn(max = 960.dp).align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = inset,
                    top = spacing.contentTopGapDp.dp,
                end = inset,
                bottom = spacing.threadBottomGapDp.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.threadItemGapDp.dp, Alignment.Bottom),
        ) {
            if (window != null) item(key = "history-older") {
                MessagePageAction(window.loading, window.failed, window.older != null, "加载更早消息") {
                    val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        (item.key as? String)?.let { it.startsWith("incoming-") || it.startsWith("outgoing-") } == true
                    }
                    anchor = visible?.let { (it.key as String) to -it.offset }
                    onLoadOlder()
                }
            }
            disclosure?.let { text ->
                item(key = "fixture-disclosure") { MessageFixtureDisclosureBand(text) }
            }
            items(shownItems, key = ::threadItemKey) { item ->
                when (item) {
                    is MessageThreadItem.DateSeparator -> MessageDateSeparator(item.date.label)
                    is MessageThreadItem.IncomingBubble -> IncomingMessageBubble(
                        item,
                        Modifier.animateItem(
                            fadeInSpec = if (!searchOpen && item.recordId == latestId) tween(duration) else null,
                            placementSpec = null,
                            fadeOutSpec = null,
                        ),
                    )
                    is MessageThreadItem.OutgoingBubble -> OutgoingMessageBubble(
                        item,
                        Modifier.animateItem(
                            fadeInSpec = if (!searchOpen && item.recordId == latestId) tween(duration) else null,
                            placementSpec = null,
                            fadeOutSpec = null,
                        ),
                    )
                }
            }
        }
        if (hasNewMessages && !searchOpen && listState.canScrollForward) {
            Button(onClick = {
                scope.launch { lastItemIndex?.let { listState.animateScrollToItem(it) }; hasNewMessages = false }
            }, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).heightIn(min = 48.dp)) {
                Text("查看新消息")
            }
        }
    }
    }
}

@Composable
private fun MessageMissingThread(state: MessageUiState, onRetry: () -> Unit) {
    val topInset = LocalSubpageTopContentInset.current
    val bottomInset = LocalSubpageBottomContentInset.current
    val panel = messageStateSpec(state) ?: StatePanelSpec(
        MiuixIcons.Info,
        StatePanelTone.NEUTRAL,
        "会话不可用",
        "该会话已不存在，请返回短信列表。",
    )
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = topInset + 16.dp, end = 16.dp, bottom = bottomInset + 16.dp),
    ) { Column { StatePanel(panel)
        if (state is MessageUiState.Failure) ManagerTextAction("重试加载", ManagerAction.Navigate, onRetry)
    } }
}

private fun threadItemKey(item: MessageThreadItem): String = when (item) {
    is MessageThreadItem.DateSeparator -> "date-${item.date.key}"
    is MessageThreadItem.IncomingBubble -> "incoming-${item.recordId}"
    is MessageThreadItem.OutgoingBubble -> "outgoing-${item.recordId}"
}

internal fun boundedDraftBody(value: String): String = value
    .filter { !it.isISOControl() || it == '\n' }
    .take(1_000)
