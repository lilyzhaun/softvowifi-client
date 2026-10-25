package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.HistoryChangeReason
import androidx.compose.animation.core.snap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.data.MessageThreadCursor
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
    historyChoices: com.vowifi.sw.manager.model.MessageHistoryChoices = com.vowifi.sw.manager.model.MessageHistoryChoices(),
    sendFlight: MessageSendFlightState = rememberMessageSendFlightState(),
) = PhoneStyle {
    val displayedState = rememberMessageRouteFrame(state, handle)
    val thread = projectMessageThread(handle, messageProjectionOwner(displayedState), displayedState, ::messageDateSpec)
    if (thread == null) {
        MessageMissingThread(displayedState, onLoadOlder)
        return@PhoneStyle
    }
    var localBody by rememberSaveable(handle.value) { mutableStateOf("") }
    val drafts = LocalThreadDrafts.current
    val body = drafts?.value?.get(handle.value) ?: localBody
    val updateBody: (String) -> Unit = { value ->
        localBody = value
        drafts?.let { it.value = it.value + (handle.value to value) }
    }
    val submission = rememberMessageDraftSubmission(state, thread.recipient?.value.orEmpty(), body, { updateBody("") }, onSend)
    val flight = sendFlight
    val flightScope = rememberCoroutineScope()
    val handoffId = submission.handoffId
    val submittedBody by rememberUpdatedState(body)
    SideEffect { flight.pendingHandoff = handoffId }
    LaunchedEffect(handoffId) {
        val id = handoffId ?: return@LaunchedEffect
        val animate = coroutineContext[MotionDurationScale]?.scaleFactor != 0f
        // Its own scope: a fast authoritative result must not cut the landing short.
        flightScope.launch { flight.fly(id, submittedBody, animate) }
    }
    // Only the presentation hands the text to its queued bubble; the retained draft returns on failure.
    val composerBody = if (handoffId != null && flight.handedOff == handoffId) "" else body
    val requestImeOwner = rememberMessageImeOwnerRequest()
    var selectedId by remember(handle) { mutableStateOf<String?>(null) }
    var actionMode by remember(handle) { mutableStateOf(SmsMessageAction.MENU) }
    val routeActive = LocalAppRouteActive.current
    var composerHeight by remember(handle) { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val loadedIds = thread.items.mapNotNull { item -> when (item) {
        is MessageThreadItem.IncomingBubble -> item.recordId
        is MessageThreadItem.OutgoingBubble -> item.recordId
        is MessageThreadItem.DateSeparator -> null
    } }
    val selectedRecord = (displayedState as? MessageUiState.Ready)?.records?.firstOrNull { it.id == selectedId && it.id in loadedIds }
    LaunchedEffect(selectedId, selectedRecord) { if (selectedRecord == null) selectedId = null }
    LaunchedEffect(routeActive) { if (!routeActive) selectedId = null }
    CommunicationFeedbackHost(bottomInset = composerHeight) {
    val copied = LocalCommunicationCopyFeedback.current
    MessageThreadActions(selectedRecord, body, thread.compose.editingEnabled && !submission.pending,
        { selectedId = null }, { submission.draftEdited(); updateBody(it) }, { copied(CommunicationCopyFeedback.MESSAGE) }, actionMode)
    CompositionLocalProvider(LocalSmsBubbleAction provides { id -> selectedId = id; actionMode = SmsMessageAction.MENU },
        LocalSmsBubbleMenu provides SmsBubbleMenu(selectedId.takeIf { actionMode == SmsMessageAction.MENU },
            (displayedState as? MessageUiState.Ready)?.records?.filter(::canReeditMessage)?.map { it.id }?.toSet().orEmpty(),
            thread.compose.editingEnabled && !submission.pending,
            { actionMode = SmsMessageAction.DETAILS }, { actionMode = SmsMessageAction.REEDIT }, { selectedId = null },
            actionsBlocked = selectedId != null)) {
    val backdrop = rememberCommunicationBackdrop()
    // 2026-10-17: the timeline runs under the floating composer; only its content padding reserves the space.
    CompositionLocalProvider(LocalMessageSendFlight provides flight) {
    Box(Modifier.fillMaxSize().background(communicationColors().timeline).onGloballyPositioned { flight.page = it }) {
        MessageThreadTimeline(
            route = AppRoute.MessageThread(handle),
            state = displayedState,
            thread = thread,
            modifier = Modifier.fillMaxSize().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            onVisible = onVisible,
            onLoadOlder = onLoadOlder,
            lineLabel = historyChoices.lines.firstOrNull { it.owner == (displayedState as? MessageUiState.Available)?.ownerId }?.lineTitle(),
            bottomInset = composerHeight,
            ownSendId = handoffId,
            flight = flight,
        )
        CompositionLocalProvider(LocalCommunicationBackdrop provides backdrop) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { composerHeight = with(density) { it.height.toDp() } }) {
        MessageComposer(
            body = composerBody,
            compose = thread.compose,
            imeOwner = messageRouteLayout(AppRoute.MessageThread(handle)).imeOwner,
            requestImeOwner = requestImeOwner,
            pending = submission.pending,
            outcome = submission.outcome,
            canSend = thread.recipient != null && body.isNotBlank(),
            onBodyChange = { submission.draftEdited(); updateBody(boundedDraftBody(it)) },
            onSend = submission.submit,
            flight = flight,
            floating = true,
        )
        }
        }
        val flying = flight.flight?.id
        MessageSendFlightOverlay(flight, thread.items.firstNotNullOfOrNull { item ->
            (item as? MessageThreadItem.OutgoingBubble)?.takeIf { flying != null && it.recordId == flying }
        })
    }
    }
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
    lineLabel: String?,
    bottomInset: Dp = 0.dp,
    ownSendId: String? = null,
    flight: MessageSendFlightState? = null,
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
    val scroll = rememberSmsScrollFeedback(listState.canScrollBackward || listState.canScrollForward)
    MessageVisibilityBinder(thread.handle, listState, onVisible)
    val window = (state as? MessageUiState.Ready)?.window
    val currentWindow by rememberUpdatedState(window)
    val active by rememberUpdatedState(LocalAppRouteActive.current)
    val inserted = rememberMessageInsertedIds(window?.change, active && !searchOpen, retainWhileItemsCompose = true)
    val currentInserted by rememberUpdatedState(inserted)
    val currentOwnSend by rememberUpdatedState(ownSendId)
    // Live ids that arrived while the reader stayed above them are history once scrolled into view.
    val quiet = remember(thread.handle, window?.change?.scope) { HashSet<String>() }
    SideEffect { flight?.landingVisible = active && !searchOpen }
    val placementChange = window?.change?.let { change ->
        if (inserted.isEmpty() || change.reason == HistoryChangeReason.LIVE_INSERT) change
        else change.copy(reason = HistoryChangeReason.LIVE_INSERT, ids = inserted)
    }
    // 跟随判断及pending anchor都使用完整时间线的索引，不把搜索命中数当作原列表尾部。
    val lastItemIndex = messageThreadLastItemIndex(thread.items.size, disclosure != null)?.plus(if (window != null) 1 else 0)
    val timelineItemCount by rememberUpdatedState(lastItemIndex?.plus(1) ?: 0)
    var anchor by remember(thread.handle, window?.change?.scope) { mutableStateOf<Pair<String, Int>?>(null) }
    var searchAnchor by remember(thread.handle, window?.change?.scope, query) { mutableStateOf<Pair<String, Int>?>(null) }
    var initialBottomReached by remember(thread.handle, window?.change?.scope) { mutableStateOf(false) }
    val latestId = (state as? MessageUiState.Ready)?.records?.lastOrNull()?.id
    var previousLastIndex by remember(thread.handle, window?.change?.scope) { mutableStateOf<Int?>(null) }
    var previousLatestId by remember(thread.handle, window?.change?.scope) { mutableStateOf<String?>(null) }
    var hasNewMessages by remember(thread.handle) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadOlder by rememberUpdatedState({
        val visible = timelineState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            (item.key as? String)?.let { it.startsWith("incoming-") || it.startsWith("outgoing-") } == true
        }
        anchor = visible?.let { (it.key as String) to -it.offset }
        onLoadOlder()
    })
    LaunchedEffect(thread.handle, latestId, window?.change?.scope, searchOpen) {
        if (searchOpen) {
            if (previousLatestId != null && latestId != previousLatestId) hasNewMessages = true
            previousLatestId = latestId
            return@LaunchedEffect
        }
        val latestIndex = lastItemIndex ?: return@LaunchedEffect
        val previousIndex = previousLastIndex
        val initial = !initialBottomReached
        val visible = snapshotFlow { timelineState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.first { it != null } ?: return@LaunchedEffect
        // A send from this composer always lands in view, even if the reader had scrolled away.
        if (latestId == currentOwnSend || shouldFollowNewMessages(initial, previousIndex ?: latestIndex, visible)) {
            timelineState.requestScrollToItem(latestIndex)
            hasNewMessages = false
        } else if (latestId != previousLatestId) {
            hasNewMessages = true
            quiet += currentInserted
        }
        previousLastIndex = latestIndex
        previousLatestId = latestId
        if (initial) {
            // R6：真实首次到底且完成布局之后，才开放near-start，不消费首屏的临时index=0。
            snapshotFlow { !timelineState.canScrollForward &&
                timelineState.layoutInfo.visibleItemsInfo.any { it.index == latestIndex } }.first { it }
            initialBottomReached = true
        }
    }
    // The floating composer's height is content padding, not viewport: keep a bottom reader at the bottom
    // in the same frame its new padding is measured, before the previous layout could hide the latest item.
    val bottomInsetPx = with(LocalDensity.current) { bottomInset.roundToPx() }
    val appliedInset = remember(thread.handle) { intArrayOf(bottomInsetPx) }
    SideEffect {
        val previous = appliedInset[0]
        appliedInset[0] = bottomInsetPx
        val count = timelineState.layoutInfo.totalItemsCount
        if (previous != bottomInsetPx && active && !searchOpen && anchor == null && currentWindow?.loading != true &&
            count == timelineItemCount && count > 0 && !timelineState.canScrollForward && !timelineState.isScrollInProgress
        ) timelineState.requestScrollToItem(count - 1)
    }
    // Keep the pre-resize bottom intent; the smaller viewport may already hide the latest item.
    LaunchedEffect(timelineState, thread.handle, window?.change?.scope, searchOpen) {
        var previousHeight: Int? = null
        var previousNearBottom = false
        snapshotFlow {
            val layout = timelineState.layoutInfo
            Triple(layout.viewportEndOffset - layout.viewportStartOffset,
                layout.visibleItemsInfo.isNotEmpty() && !timelineState.canScrollForward, layout.totalItemsCount)
        }.collect { (height, nearBottom, count) ->
            val resized = previousHeight != null && previousHeight != height
            val follow = resized && previousNearBottom && active && !searchOpen && anchor == null &&
                currentWindow?.loading != true && count == timelineItemCount && count > 0
            if (follow) timelineState.requestScrollToItem(count - 1)
            previousHeight = height
            previousNearBottom = if (follow) true else nearBottom
        }
    }
    LaunchedEffect(searching, thread.handle, window?.change?.scope, query) {
        if (searching) snapshotFlow {
            if (currentWindow?.loading == true) searchState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                (item.key as? String)?.let { it.startsWith("incoming-") || it.startsWith("outgoing-") } == true
            }?.let { (it.key as String) to -it.offset } else null
        }.collect { saved -> if (saved != null) searchAnchor = saved }
    }
    LaunchedEffect(thread.items, window?.loading, searching) {
        val saved = anchor
        if (!searching && saved != null && window?.loading == false) {
            // R6：pending anchor属于完整时间线；搜索列表及其独立key/offset永不消费它。
            val index = thread.items.indexOfFirst { threadItemKey(it) == saved.first }
            if (index >= 0) {
                timelineState.requestScrollToItem(index + (if (disclosure != null) 1 else 0) + 1, saved.second)
                // 请求不等于已布局。history-older作为首key时，新页首layout仍可能停在index=0；
                // 必须看到原消息key和offset实际恢复，才释放同一anchor并允许下一cursor。
                snapshotFlow {
                    val layout = timelineState.layoutInfo
                    layout.totalItemsCount == timelineItemCount && layout.visibleItemsInfo.any {
                        it.key == saved.first && it.offset == -saved.second
                    }
                }.first { it }
            }
            anchor = null
        }
        val searched = searchAnchor
        if (searching && searched != null && window?.loading == false) {
            // 搜索首可见key可能是历史头/日期。以自己的真实消息锚抵消prepend及头高变化。
            val index = shownItems.indexOfFirst { threadItemKey(it) == searched.first }
            if (index >= 0) searchState.requestScrollToItem(index + (if (disclosure != null) 1 else 0) + 1, searched.second)
            searchAnchor = null
        }
        if (latestId == previousLatestId) previousLastIndex = lastItemIndex
    }
    LaunchedEffect(timelineState, thread.handle, window?.change?.scope) {
        var requested: MessageThreadCursor? = null
        snapshotFlow {
            val current = currentWindow
            val layout = timelineState.layoutInfo
            // 新页窗口不能消费旧页尚未恢复锚点的layout，避免旧首屏连发下一cursor。
            val nearStart = layout.totalItemsCount == timelineItemCount && layout.visibleItemsInfo
                .firstOrNull { it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
                ?.index?.let { it <= 2 } == true
            if (active && initialBottomReached && anchor == null && !searchOpen && current != null &&
                !current.loading && !current.failed && nearStart) current.older else null
        }.collect { cursor ->
            if (cursor != null && cursor != requested) { requested = cursor; loadOlder() }
        }
    }
    Column(modifier.fillMaxWidth().padding(top = topInset + 8.dp).clipToBounds()) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        MessageThreadSearch(searchOpen, query, shownItems.count { it !is MessageThreadItem.DateSeparator },
            onToggle = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, onQuery = { value ->
                if (query != value) {
                    query = value
                    searchState.requestScrollToItem(0)
                }
            },
            contextLabel = if (thread.compose.mode == MessageComposeMode.READ_ONLY) "只读：草稿可编辑，暂不可发送" else lineLabel)
        if (searchOpen) CommunicationTextAction("回到最新", {
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
            modifier = Modifier.fillMaxSize().widthIn(max = 960.dp).align(Alignment.TopCenter)
                .messageReplacementFade(window?.change, thread.handle to query)
                .managerImeScroll(listState, enabled = !searchOpen).then(scroll.modifier),
            overscrollEffect = scroll.effect,
            contentPadding = PaddingValues(
                start = inset,
                    top = spacing.contentTopGapDp.dp,
                end = inset,
                bottom = bottomInset + spacing.threadBottomGapDp.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.threadItemGapDp.dp, if (searching) Alignment.Top else Alignment.Bottom),
        ) {
            if (window != null) item(key = "history-older") {
                MessagePageAction(window.loading, window.failed, window.older != null, "加载更早消息", onLoad = loadOlder)
            }
            disclosure?.let { text ->
                item(key = "fixture-disclosure") { MessageFixtureDisclosureBand(text) }
            }
            if (searching && shownItems.isEmpty()) item(key = "thread-search-empty") {
                CommunicationListState(StatePanelSpec(CommunicationSearch, StatePanelTone.NEUTRAL,
                    "未找到匹配消息", "仅搜索已加载消息。可更换关键词，或关闭搜索查看会话。"))
            }
            items(shownItems, key = ::threadItemKey) { item ->
                val liveId = when (item) {
                    is MessageThreadItem.IncomingBubble -> item.recordId
                    is MessageThreadItem.OutgoingBubble -> item.recordId
                    is MessageThreadItem.DateSeparator -> null
                }
                // 仅记该item首次呈现的资格；CONTENT不撤掉正在运行的入场，查询/scope切换重新建基线。
                var presented by rememberSaveable(thread.handle.value, liveId, window?.change?.scope, searchOpen, query) { mutableStateOf(false) }
                val enter = remember(thread.handle, liveId, window?.change?.scope, searchOpen, query) {
                    // A composer send is carried by its flight instead of the standard MO entrance.
                    !presented && liveId != null && liveId in inserted && liveId != ownSendId && liveId !in quiet
                }
                // Only a new newest row is pushed in from behind the composer; an older-timestamp MT keeps its fade.
                val rise = remember(thread.handle, liveId, window?.change?.scope, searchOpen, query) { enter && liveId == latestId }
                SideEffect { presented = true }
                // A live bubble can sort before older items; only its local entrance raises it.
                // Let Lazy retain its whole-item placement coordinates; only the independent layer animates.
                val placement = Modifier.animateItem(fadeInSpec = null, placementSpec = snap(), fadeOutSpec = null)
                    .zIndex(if (enter) 1f else 0f)
                    .messageThreadItemPlacement(placementChange, thread.handle to query, active && !searchOpen, listState)
                when (item) {
                    is MessageThreadItem.DateSeparator -> MessageDateSeparator(item.date.label)
                    is MessageThreadItem.IncomingBubble -> IncomingMessageBubble(item, placement, enter = enter, rise = rise)
                    is MessageThreadItem.OutgoingBubble -> OutgoingMessageBubble(item, placement, enter = enter)
                }
            }
        }
        MessageNewMessagesHint(
            visible = hasNewMessages && !searchOpen && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
        ) {
            scope.launch { lastItemIndex?.let { listState.animateScrollToItemFromNearby(it) }; hasNewMessages = false }
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
    ) { CommunicationListState(panel) {
        if (state is MessageUiState.Failure) CommunicationTextAction("重试", onRetry)
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
