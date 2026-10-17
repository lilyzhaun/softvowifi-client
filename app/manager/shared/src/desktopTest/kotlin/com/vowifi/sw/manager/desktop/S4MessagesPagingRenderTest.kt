@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.HistoryChangeReason
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.math.abs
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Scaffold

/** S4已运行入口；与MessagePagingRenderTest复用生产selection，不改旧守护。 */
class S4MessagesPagingRenderTest {
    @Test fun nearEndStartsOneReadAndLoadingDoesNotRepeatIt() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = false).use { rig ->
            rig.gate = CompletableDeferred()
            MotionTestEnvironment(testScheduler, 420, 900) { rig.Content(false) }.use { e ->
                e.s4Frames(12)
                assertEquals(50, rig.inbox.page.items.size); assertEquals(0, rig.inboxCalls)
                s4WheelUntil(e, 8f) { s4Visible(e, "p-71", inList = true) != null }
                e.s4Frames(12)
                assertEquals(1, rig.inboxCalls, "R6：真实近底滚动必须消费 onLoadMore 一次")
                assertEquals(1, rig.inboxReads); assertTrue(rig.inbox.page.loading)
                repeat(3) { e.scroll(s4List(e).boundsInRoot.center, Offset(0f, 3f)); e.s4Frames(6) }
                assertEquals(1, rig.inboxCalls); assertEquals(1, rig.inboxReads)
                rig.gate!!.complete(Unit); e.s4Frames(12)
                assertEquals(100, rig.inbox.page.items.size)
                assertFalse(rig.inbox.page.loading); assertEquals(1, rig.inboxCalls)
            }
        }
    } }

    @Test fun failedPageWaitsForOneRealPointerRetry() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = false).use { rig ->
            rig.failNext = true
            MotionTestEnvironment(testScheduler, 420, 900) { rig.Content(false) }.use { e ->
                e.s4Frames(12)
                s4WheelUntil(e, 8f) { rig.inboxCalls > 0 || s4Visible(e, "加载更多会话", inList = true) != null }
                // 隔离 failed/retry：旧 API 无自动入口时，也必须用真实按钮得到第一次真实失败。
                if (rig.inboxCalls == 0) s4Tap(e, "加载更多会话")
                e.advanceFrameBy(0); e.s4Frames(12)
                println("S4_SMS_INBOX|phase=afterManual|calls=${rig.inboxCalls}|reads=${rig.inboxReads}|failed=${rig.inbox.page.failed}")
                assertEquals(1, rig.inboxCalls); assertTrue(rig.inbox.page.failed)
                if (s4Visible(e, "加载失败，重试加载", inList = true) == null)
                    s4WheelUntil(e, 3f) { s4Visible(e, "加载失败，重试加载", inList = true) != null }
                e.s4Frames(40)
                assertEquals(1, rig.inboxCalls); assertEquals(1, rig.inboxReads)
                rig.gate = CompletableDeferred()
                s4Tap(e, "加载失败，重试加载"); e.s4Frames(2)
                assertEquals(2, rig.inboxCalls, "命中前提：真实重试点击必须到达生产回调")
                assertEquals(2, rig.inboxReads); assertTrue(rig.inbox.page.loading)
                rig.gate!!.complete(Unit); e.s4Frames(12)
                assertEquals(100, rig.inbox.page.items.size); assertFalse(rig.inbox.page.failed)
            }
        }
    } }

    @Test fun exhaustedFooterNeverStartsAnotherRead() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = false, count = 50).use { rig ->
            MotionTestEnvironment(testScheduler, 420, 900) { rig.Content(false) }.use { e ->
                e.s4Frames(12); assertNull(rig.inbox.page.next)
                s4WheelUntil(e, 8f) { s4Visible(e, "已显示全部历史", inList = true) != null }
                val point = s4Visible(e, "已显示全部历史", inList = true)!!.boundsInRoot.center
                e.press(point); e.release(point); e.s4Frames(40)
                assertEquals(0, rig.inboxCalls); assertEquals(0, rig.inboxReads)
                assertEquals(50, rig.inbox.page.items.size)
            }
        }
    } }

    @Test fun actualLiveConversationMoveHasMiddlePositionsButQueryReplacementDoesNotReorderThroughThem() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = false, count = 3).use { rig ->
            val transport = FakeEngineMessageTransport()
            val source = EngineMessageSource(
                runtime = EngineMessageRuntime(
                    listInstances = { listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)) },
                    transportFor = { transport }, clock = { EngineMessagePersistenceFixtures.NOW_MS },
                    activeIccids = { listOf(EngineMessagePersistenceFixtures.ICCID_A) }),
                parentContext = coroutineContext, repository = rig.stored,
                ownerIdFor = { if (it == EngineMessagePersistenceFixtures.ICCID_A) rig.owner else null },
                nextMessageId = sequentialIds("s4-live-move"),
            )
            try {
                testScheduler.runCurrent()
                fun inbox() = assertIs<MessageUiState.Inbox>(source.state.value)
                val moved = inbox().page.items.single { it.latestMessageId == "id-0" }.identity
                val peer = requireNotNull(rig.stored.stored("id-0")).peerAddress
                MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                    MessagesScreen(source.state.collectAsState().value, {}, {},
                        historyChoices = source.historyChoices.collectAsState().value,
                        onSearchHistory = source::searchHistory, onLoadMore = source::loadMoreHistory)
                } }.use { e ->
                    e.s4Frames(32)
                    val initial = assertNotNull(s4Visible(e, "p-2", inList = true)).boundsInRoot.top
                    val beforeHandles = inbox().page.items.map { it.identity.handle }
                    assertEquals(moved.handle, beforeHandles.last())
                    transport.emitReceived("s4-move-event", peer, "moved-zero", EngineMessagePersistenceFixtures.NOW_MS / 1_000)
                    testScheduler.runCurrent()
                    val change = assertNotNull(inbox().page.change)
                    assertEquals(HistoryChangeReason.LIVE_MOVE, change.reason)
                    assertEquals(setOf(moved.handle), change.ids)
                    assertEquals(moved.handle, inbox().page.items.first().identity.handle)
                    e.advanceFrameBy(0); e.s4Frames(2)
                    val middle = assertNotNull(s4Visible(e, "p-2", inList = true)).boundsInRoot.top
                    // 用已有真实已读路径产生CONTENT；基础FakeRepository并未实现changeConversation，不能把Unit或默认失败当成功。
                    val received = inbox().page.items.first()
                    assertTrue(received.unreadCount > 0)
                    source.markVisibleRead(assertNotNull(ObservedIncomingReadRequest.create(
                        rig.owner, moved.key, listOf(received.latestMessageId))))
                    testScheduler.runCurrent()
                    assertTrue(assertNotNull(rig.stored.stored(received.latestMessageId)).isRead, "必须实际写入已读，而非手改UI快照")
                    assertEquals(received.unreadCount - 1, inbox().page.items.first().unreadCount)
                    val content = assertNotNull(inbox().page.change)
                    assertEquals(HistoryChangeReason.CONTENT, content.reason)
                    assertTrue(content.scope === change.scope && content.version > change.version)
                    e.advanceFrameBy(0); e.s4Frames(2)
                    val duringContent = assertNotNull(s4Visible(e, "p-2", inList = true)).boundsInRoot.top
                    e.s4Frames(48)
                    val end = assertNotNull(s4Visible(e, "p-2", inList = true)).boundsInRoot.top
                    assertTrue(end > initial + 24f, "前提：真实来信已将原末会话移到根列表首部")
                    assertTrue(middle > initial + .5f && middle < end - .5f,
                        "E2：真实LIVE_MOVE须让兄弟行经历SpringSpatial位置中间帧，而不是瞬间重排")
                    assertTrue(duringContent > middle + .5f && duringContent < end - .5f,
                        "E2：真实CONTENT读回不能截断既有补位；middle=$middle content=$duringContent end=$end")
                    assertEquals(beforeHandles.toSet(), inbox().page.items.map { it.identity.handle }.toSet())
                    assertEquals(4, rig.stored.listByOwner(rig.owner).size)
                    s4Tap(e, "搜索短信"); e.s4Frames(24)
                    val beforeQuery = assertNotNull(s4Visible(e, "p-2", inList = true)).boundsInRoot.top
                    val field = s4Nodes(e).single { it.config.contains(SemanticsActions.SetText) }
                    assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action)
                        .invoke(androidx.compose.ui.text.AnnotatedString("p-2")))
                    e.advanceFrameBy(0); e.s4Frames(2)
                    assertEquals("p-2", inbox().page.query)
                    assertEquals(HistoryChangeReason.REPLACE, assertNotNull(inbox().page.change).reason)
                    assertEquals(listOf(beforeHandles.first()), inbox().page.items.map { it.identity.handle })
                    val replaced = assertNotNull(s4Visible(e, "p-2", inList = true)).boundsInRoot.top
                    e.s4Frames(32)
                    val settled = assertNotNull(s4Visible(e, "p-2", inList = true)).boundsInRoot.top
                    assertTrue(beforeQuery - settled > 24f, "前提：查询确已移除首部会话并改变实际行位置")
                    assertEquals(settled, replaced, .5f, "E2/R6：REPLACE只有整列表淡入，不能沿旧LIVE_MOVE轨迹补位")
                    assertEquals(4, rig.stored.listByOwner(rig.owner).size)
                    assertTrue(transport.requests.isEmpty(), "本合同仅真实MT和本地已读，不调用发送RPC")
                }
            } finally { source.close(); testScheduler.runCurrent(); rig.stored.close() }
        }
    } }
}

/** 仅在真实 repository 页读取处设置屏障/失败；UI 状态全部由现有 selection 发布。 */
internal class S4SmsHistoryRig private constructor(scheduler: TestCoroutineScheduler) : AutoCloseable {
    val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    val identity = StoredMessageConversation(owner, "1555")
    val stored = FakeMessageRepository()
    var gate: CompletableDeferred<Unit>? = null
    var failNext = false
    var inboxCalls = 0; var olderCalls = 0
    var inboxReads = 0; var olderReads = 0
    var atOlderRequest: (() -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private suspend fun pageBarrier() {
        gate?.await()
        if (failNext) { failNext = false; throw MessageStorageException() }
    }
    private val repository = object : MessageRepository by stored {
        override val history = object : MessageHistoryRepository by stored.history {
            override suspend fun summaries(request: MessageSummaryRequest): MessageSummaryPage {
                if (request.cursor != null) { inboxReads++; pageBarrier() }
                return stored.history.summaries(request)
            }
            override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
                if (cursor != null) { olderReads++; pageBarrier() }
                return stored.history.thread(identity, cursor)
            }
        }
    }
    val selection = MessageHistorySelection(repository, scope, MessagePersistenceObserver { _, _, _ -> })
    val inbox get() = assertIs<MessageUiState.Inbox>(selection.state.value)
    val thread get() = assertIs<MessageUiState.Ready>(selection.state.value)
    @Composable fun Content(thread: Boolean) = s4SmsHost {
        val state = selection.state.collectAsState().value
        if (thread) MessageThreadScreen(identity.handle, state, { _, _ -> error("只读路径不能发送") },
            onLoadOlder = { olderCalls++; println("S4_SMS_CALLBACK|kind=older|calls=$olderCalls|reads=$olderReads")
                atOlderRequest?.invoke(); selection.loadOlder() })
        else MessagesScreen(state, {}, {}, historyChoices = selection.historyChoices.collectAsState().value,
            onSearchHistory = selection::searchInbox, onLoadMore = { inboxCalls++
                println("S4_SMS_CALLBACK|kind=inbox|calls=$inboxCalls|reads=$inboxReads"); selection.loadMoreInbox() })
    }
    override fun close() { selection.close(); scope.cancel() }
    companion object {
        suspend fun create(scheduler: TestCoroutineScheduler, sameThread: Boolean, count: Int = 121): S4SmsHistoryRig {
            val rig = S4SmsHistoryRig(scheduler)
            repeat(count) { i -> rig.stored.insertIncomingIfAbsent(rig.owner, "id-$i", "engine-$i",
                if (sameThread) "1555" else "1555$i", "p-$i", i.toLong(), 1) }
            if (sameThread) rig.selection.select(rig.identity.handle.value) else rig.selection.refreshInbox()
            scheduler.runCurrent()
            return rig
        }
    }
}

@Composable internal fun s4SmsHost(content: @Composable () -> Unit) {
    val lifecycle = remember { object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    } }
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle,
        LocalPopupPresentationEpoch provides remember { mutableIntStateOf(0) }) {
        SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize(), popupHost = { com.vowifi.sw.manager.ui.ManagerPopupHost() }) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                // 只观察真实输入的 Final pass；不消费、转发或生成点击，也不记录短信正文。
                awaitPointerEventScope { while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.type == PointerEventType.Press || event.type == PointerEventType.Release)
                        println("S4_SMS_POINTER|pass=Final|event=${event.type}|changes=" + event.changes.joinToString { change ->
                            "t=${change.uptimeMillis},type=${change.type},position=${change.position}," +
                                "previousPressed=${change.previousPressed},pressed=${change.pressed},consumed=${change.isConsumed}"
                        })
                } }
            }) { content() }
        } }
    }
}
internal fun MotionTestEnvironment.s4Frames(count: Int = 1) { repeat(count) { advanceFrameBy(16) } }
internal fun s4Nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
    fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
    descend(owner.rootSemanticsNode)
}
internal fun s4HasLabel(n: SemanticsNode, label: String) =
    n.config.getOrNull(SemanticsProperties.Text)?.any { stripMessageDisplayJoiners(it.text) == label } == true ||
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
internal fun s4List(e: MotionTestEnvironment) = s4Nodes(e).single { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
internal fun s4Visible(e: MotionTestEnvironment, label: String, inList: Boolean = false): SemanticsNode? {
    val viewport = if (inList) s4List(e).boundsInRoot else androidx.compose.ui.geometry.Rect(0f, 0f, 420f, 900f)
    return s4Nodes(e).firstOrNull { n -> val b = n.boundsInRoot
        s4HasLabel(n, label) && b.height >= 8f && b.width >= 8f && b.top >= viewport.top &&
            b.bottom <= viewport.bottom && b.left >= viewport.left && b.right <= viewport.right }
}
internal fun s4Tap(e: MotionTestEnvironment, label: String) {
    fun action() = s4Nodes(e).single { s4HasLabel(it, label) && it.config.getOrNull(SemanticsActions.OnClick) != null }
    val paging = label in listOf("加载更多会话", "加载更早消息", "加载失败，重试加载")
    if (paging) {
        // 观测位置／真实滚动轴稳定后只点一次；不盲等一个更长时长，也不重试未命中的点击。
        var previous: Triple<Rect, Rect, Float>? = null
        var previousPosition: Offset? = null
        var previousOwner: Int? = null
        var stableFrames = 0
        for (sample in 0..32) {
            val target = action()
            val list = s4List(e)
            val snapshot = Triple(target.boundsInRoot, list.boundsInRoot,
                list.config[SemanticsProperties.VerticalScrollAxisRange].value())
            stableFrames = if (snapshot == previous && target.positionInRoot == previousPosition && target.id == previousOwner)
                stableFrames + 1 else 0
            println("S4_SMS_TAP|label=$label|phase=settle|sample=$sample|owner=${target.id}|bounds=${snapshot.first}|" +
                "position=${target.positionInRoot}|size=${target.size}|viewport=${snapshot.second}|axis=${snapshot.third}|" +
                "disabled=${target.config.contains(SemanticsProperties.Disabled)}|stableFrames=$stableFrames")
            if (stableFrames >= 2) break
            assertTrue(sample < 32, "命中前提：真实按钮／viewport／滚动轴未稳定，不能计产品行为 RED")
            previous = snapshot
            previousPosition = target.positionInRoot
            previousOwner = target.id
            e.advanceFrameBy(16)
        }
    }
    val n = action()
    val b = n.boundsInRoot
    assertTrue(b.width >= 24f && b.height >= 24f && b.left >= 0f && b.right <= 420f && b.top >= 0f && b.bottom <= 900f,
        "指针前提：真实命中矩形必须完整可见，label=$label bounds=$b")
    val ancestors = generateSequence(n) { it.parent }.toList()
    val disabled = ancestors.filter { it.config.contains(SemanticsProperties.Disabled) }.map { it.id }
    val overlaps = s4Nodes(e).filter { candidate -> candidate.id !in ancestors.map { it.id } &&
        candidate.config.contains(SemanticsActions.OnClick) && candidate.boundsInRoot.contains(b.center) }
        .map { "${it.id}@${it.boundsInRoot}:disabled=${it.config.contains(SemanticsProperties.Disabled)}" }
    println("S4_SMS_TAP|label=$label|phase=beforePress|owner=${n.id}|bounds=$b|position=${n.positionInRoot}|size=${n.size}|" +
        "ancestorIds=${ancestors.map { it.id }}|disabledAncestorIds=$disabled|overlapCandidates=$overlaps|sceneOwners=${e.scene.semanticsOwners.size}")
    assertTrue(disabled.isEmpty(), "命中前提：当前真实动作或祖先disabled，不能计回调产品 RED")
    if (paging) {
        val viewport = s4List(e).boundsInRoot
        assertTrue(b.left >= viewport.left && b.right <= viewport.right && b.top >= viewport.top && b.bottom <= viewport.bottom,
            "命中前提：分页按钮必须完整位于真实列表viewport，bounds=$b viewport=$viewport")
    }
    e.press(b.center); e.advanceFrameBy(8)
    val held = s4Nodes(e).singleOrNull { it.id == n.id }
    println("S4_SMS_TAP|label=$label|phase=beforeRelease|point=${b.center}|owner=${n.id}|bounds=${held?.boundsInRoot}|" +
        "disabled=${held?.config?.contains(SemanticsProperties.Disabled)}|holdMillis=8")
    e.release(b.center)
}
internal fun s4WheelUntil(e: MotionTestEnvironment, delta: Float, reached: () -> Boolean) {
    val before = s4List(e).config[SemanticsProperties.VerticalScrollAxisRange].value()
    repeat(64) { if (!reached()) { e.scroll(s4List(e).boundsInRoot.center, Offset(0f, delta)); e.s4Frames(6) } }
    assertTrue(reached(), "滚动定位前提未成立，不能当产品行为 RED")
    val after = s4List(e).config[SemanticsProperties.VerticalScrollAxisRange].value()
    assertTrue(abs(after - before) > 1f, "滚轮必须真实命中当前生产列表")
}
internal fun s4Anchor(e: MotionTestEnvironment): Pair<String, Float> {
    val viewport = s4List(e).boundsInRoot
    val node = s4Nodes(e).filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("p-") } == true &&
        n.boundsInRoot.height >= 8f && n.boundsInRoot.top >= viewport.top && n.boundsInRoot.bottom <= viewport.bottom }
        .minBy { it.boundsInRoot.top }
    return node.config[SemanticsProperties.Text].first { it.text.startsWith("p-") }.text to node.boundsInRoot.top
}
internal fun s4InkRatio(middle: S3SmallPatch, end: S3SmallPatch): Double {
    assertEquals(end.width, middle.width); assertEquals(end.height, middle.height)
    val background = end.pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
    val core = end.pixels.indices.filter { s3RgbDistance(end.pixels[it], background) >= 48 }
    assertTrue(core.size >= 3, "像素前提：自然小文字矩形须有至少三个高对比核心点")
    return core.map { s3RgbDistance(middle.pixels[it], background).toDouble() / s3RgbDistance(end.pixels[it], background) }.average()
}
