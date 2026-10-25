package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.MessageThreadCursor
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures
import com.vowifi.sw.manager.data.EngineMessageRuntime
import com.vowifi.sw.manager.data.EngineMessageSource
import com.vowifi.sw.manager.data.FakeEngineMessageTransport
import com.vowifi.sw.manager.data.FakeMessageRepository
import com.vowifi.sw.manager.data.MessageHistoryRepository
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.MessageThreadSlice
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SmsProductionNavigationTest {
    @Test fun acceptedNewMessageReplacesItsPageAndBackDoesNotReopenTheComposer() = onMotionTestThread {
        for (status in listOf(OutgoingMessageStatus.ACCEPTED, OutgoingMessageStatus.DELIVERY_REPORTED)) {
            Driver(AppRoute.NewMessage).use { driver ->
                driver.edit(0, peer); driver.edit(1, "synthetic accepted draft"); driver.send()
                assertTrue(driver.hasText("新建短信"), "受理前仍保留真实新建页")
                driver.complete(status)
                assertFalse(driver.hasText("新建短信"), "本次受理/报告后应替换为会话，而不是只清稿仍留在新建页")
                assertEquals(identity.handle.value, driver.source.selected)
                driver.click("返回")
                assertFalse(driver.hasText("新建短信"), "替换栈顶后返回不能重新露出新建页")
                assertEquals(null, driver.source.selected)
                assertEquals(1, driver.source.sends)
            }
        }
    }

    @Test fun failedAndUnknownNewMessageStayOnThePageWithTheOriginalDraft() = onMotionTestThread {
        for (status in listOf(OutgoingMessageStatus.FAILED, OutgoingMessageStatus.UNKNOWN)) {
            Driver(AppRoute.NewMessage).use { driver ->
                driver.edit(0, peer); driver.edit(1, "synthetic retained draft"); driver.send(); driver.complete(status)
                assertTrue(driver.hasText("新建短信"))
                assertEquals("synthetic retained draft", driver.fieldText(1))
                assertEquals(null, driver.source.selected)
                assertEquals(1, driver.source.sends)
            }
        }
    }

    @Test fun lateAcceptanceCannotReplaceTheUsersNewerNavigation() = onMotionTestThread {
        Driver(AppRoute.NewMessage).use { driver ->
            driver.edit(0, peer); driver.edit(1, "synthetic late draft"); driver.send()
            driver.route(AppRoute.SettingsAbout)
            assertTrue(driver.hasText("关于"))
            driver.complete()
            assertTrue(driver.hasText("关于"), "迟到受理不能把用户的新导航替换成会话")
            assertEquals(null, driver.source.selected)
            assertEquals(1, driver.source.sends)
        }
    }

    @Test fun productionAcceptanceOpensThePersistedThreadThroughTheRealSendPath() = onMotionTestThread { runTest(timeout = 20.seconds) {
        val stored = FakeMessageRepository()
        val transport = FakeEngineMessageTransport(sendResult = EngineMessagePersistenceFixtures.sendOk("accepted-engine-id", 202, 1))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = EngineMessageSource(EngineMessageRuntime(
            { listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)) },
            { transport }, { 1_700_000_000_000L }, { listOf(EngineMessagePersistenceFixtures.ICCID_A) }),
            backgroundScope.coroutineContext + dispatcher, stored, { owner }, { "accepted-local-id" })
        val paging = PagingSource(backend, testScheduler, dispatcher)
        try { Driver(AppRoute.NewMessage, paging).use { driver ->
            assertTrue(transport.statusRequests > 0)
            driver.edit(0, peer); driver.edit(1, "synthetic production body"); driver.send()
            val record = stored.listByOwner(owner).filterIsInstance<MessageRecord.Outgoing>().single()
            assertEquals(OutgoingMessageStatus.ACCEPTED, record.status)
            assertEquals(1, transport.requests.count { it.method == "sendSms" })
            assertFalse(driver.hasText("新建短信"))
            assertEquals(identity.handle.value, paging.selected)
            assertTrue(driver.hasText("synthetic production body"))
            driver.click("返回")
            assertEquals(null, paging.selected)
            assertFalse(driver.hasText("新建短信"))
        } } finally { backend.close(); runCurrent(); stored.close() }
    } }

    @Test fun existingConversationOutsideTheInboxPageCanOpenWithoutSendingOrDiscardingBody() = onMotionTestThread { runTest(timeout = 20.seconds) {
        val stored = FakeMessageRepository()
        val epoch = 1_700_000_000_000L
        stored.insertIncomingIfAbsent(owner, "old-peer", "old-engine", peer, "synthetic old conversation", epoch, epoch)
        repeat(55) { index -> stored.insertIncomingIfAbsent(owner, "new-$index", "engine-$index", "+1202556${index.toString().padStart(4, '0')}",
            "synthetic newer conversation", epoch + (index + 1) * 60000L, epoch) }
        val transport = FakeEngineMessageTransport()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = EngineMessageSource(EngineMessageRuntime(
            { listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)) },
            { transport }, { epoch + 60 * 60000L }, { listOf(EngineMessagePersistenceFixtures.ICCID_A) }),
            backgroundScope.coroutineContext + dispatcher, stored, { owner }, { "unused-new-id" })
        val paging = PagingSource(backend, testScheduler, dispatcher)
        try { Driver(AppRoute.NewMessage, paging).use { driver ->
            val inbox = backend.state.value as MessageUiState.Inbox
            assertEquals(50, inbox.page.items.size)
            assertTrue(inbox.page.items.none { it.identity == identity }, "前提：目标真实记录不在已加载首页")
            assertTrue(transport.statusRequests > 0)
            driver.edit(0, peer)
            assertTrue(driver.hasText("此线路已有与该号码的会话"), "存在提示必须查真实owner历史，不能只看首页summary")
            assertTrue(driver.hasText("打开会话"))
            driver.edit(1, "synthetic unsent body")
            assertTrue(driver.hasText("此线路已有与该号码的会话"))
            assertFalse(driver.hasText("打开会话"), "非空正文只提示，不能转移或丢弃草稿")
            assertEquals("synthetic unsent body", driver.fieldText(1))
            driver.edit(1, "")
            driver.click("打开会话")
            assertEquals(identity.handle.value, paging.selected)
            assertFalse(driver.hasText("新建短信"))
            driver.click("返回")
            assertEquals(null, paging.selected)
            assertFalse(driver.hasText("新建短信"))
            assertTrue(transport.requests.none { it.method == "sendSms" })
        } } finally { backend.close(); runCurrent(); stored.close() }
    } }

    @Test fun pendingNewMessageDepartureReturnAndEditBackCannotLateClearDraft() = onMotionTestThread {
        Driver(AppRoute.NewMessage).use { driver ->
            driver.edit(0, peer)
            driver.edit(1, "synthetic draft")
            driver.send()
            driver.route(AppRoute.Messages)
            assertEquals(null, driver.source.selected)
            driver.route(AppRoute.NewMessage)
            assertEquals("synthetic draft", driver.fieldText(1))
            driver.releaseBusy()
            driver.edit(1, "new synthetic draft")
            driver.edit(1, "synthetic draft")
            driver.complete()
            assertEquals("synthetic draft", driver.fieldText(1))
            assertFalse(driver.hasText("已受理，不代表接收设备已收到。"))
            assertEquals(1, driver.source.sends)
        }
    }

    @Test fun pendingThreadDepartureReturnCannotLateClearNewerDraft() = onMotionTestThread {
        Driver(AppRoute.MessageThread(identity.handle)).use { driver ->
            driver.edit(0, "synthetic draft")
            driver.send()
            driver.route(AppRoute.Messages)
            driver.route(AppRoute.MessageThread(identity.handle))
            assertEquals("synthetic draft", driver.fieldText(0))
            driver.releaseBusy()
            driver.edit(0, "new synthetic draft")
            driver.complete()
            assertEquals("new synthetic draft", driver.fieldText(0))
            assertEquals(1, driver.source.sends)
        }
    }

    @Test fun earlierPageFinishesDuringSearchAndHiddenRouteDoesNotObserveIncoming() = onMotionTestThread { runTest(timeout = 20.seconds) {
        val stored = FakeMessageRepository()
        val epoch = 1_700_000_000_000L
        // 121条合成记录走repository分页：最新50条含原id-25；第二页只并入50条，到100而非121。
        for (index in -81..39) stored.insertIncomingIfAbsent(owner, "id-$index", "engine-$index", peer,
            "synthetic message $index", epoch + index * 60000L, epoch)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        var cursorReads = 0
        val repository = object : MessageRepository by stored {
            override val history = object : MessageHistoryRepository by stored.history {
                override suspend fun thread(identity: StoredMessageConversation, cursor: MessageThreadCursor?): MessageThreadSlice {
                    if (cursor != null) {
                        cursorReads++; entered.complete(Unit)
                        withTimeout(20_000) { release.await() }
                    }
                    val slice = stored.history.thread(identity, cursor)
                    if (cursor != null) completed.complete(Unit)
                    return slice
                }
            }
        }
        val transport = FakeEngineMessageTransport()
        var incomingId = "unused"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = EngineMessageSource(
            EngineMessageRuntime({ listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)) },
                { transport }, { epoch + 60 * 60000L }, { listOf(EngineMessagePersistenceFixtures.ICCID_A) }),
            backgroundScope.coroutineContext + dispatcher, repository, { owner }, { incomingId })
        val paging = PagingSource(backend, testScheduler, dispatcher)
        try { Driver(AppRoute.MessageThread(identity.handle), paging).use { driver ->
            assertEquals(50, paging.ready.records.size)
            assertEquals(0, paging.loads); assertEquals(0, cursorReads)
            assertTrue(transport.statusRequests > 0, "生产消息源须已接通测试transport并发布状态")
            driver.scrollToStart()
            assertTrue(entered.isCompleted, "近首必须自动到达真实repository，不再抢点已被loading替换的旧按钮")
            assertEquals(1, paging.loads); assertEquals(1, cursorReads)
            assertEquals(50, paging.ready.records.size); assertTrue(paging.ready.window?.loading == true)
            assertFalse(completed.isCompleted, "仓库门闸未放行不能伪装更早页已完成")
            paging.observed.clear()
            driver.click("搜索会话")
            driver.edit(0, "message 25")
            assertTrue(driver.hasText("仅搜索已加载消息 · 1 条"), "search field must filter the production thread")
            assertTrue(paging.observed.isNotEmpty(), "production visibility binder must be active")
            assertTrue(paging.observed.any { it.second.contains("id-25") })
            val searchAnchor = driver.textTop("synthetic message 25")
            paging.observed.clear()
            release.complete(Unit)
            driver.frame()
            assertTrue(completed.isCompleted, "更早页必须经真实repository完成")
            assertEquals(100, paging.ready.records.size, "一次R6只并入第二页，不放宽成全量121")
            assertFalse(paging.ready.window?.loading == true)
            assertTrue(paging.ready.records.any { it.id == "id--60" })
            assertEquals(1, paging.loads); assertEquals(1, cursorReads)
            assertTrue(driver.hasText("仅搜索已加载消息 · 1 条"))
            assertEquals(searchAnchor, driver.textTop("synthetic message 25"), 2f, "真实older完成不能移动当前搜索锚点")
            assertTrue(paging.observed.all { (_, ids) -> ids.all { it == "id-25" } })
            // 后继合成MT也走生产事件解码/入库/读回路径；不再手填Ready伪装读取完成。
            incomingId = "id-40"
            transport.emitReceived("engine-40", peer, "synthetic message 40", (epoch + 40 * 60000L) / 1000)
            driver.frame()
            assertTrue(paging.ready.records.any { it.id == "id-40" })
            assertTrue(paging.observed.all { (_, ids) -> ids.all { it == "id-25" } })
            driver.route(AppRoute.Messages)
            paging.observed.clear()
            incomingId = "id-41"
            transport.emitReceived("engine-41", peer, "synthetic message 41", (epoch + 41 * 60000L) / 1000)
            driver.frame()
            assertTrue(stored.listByOwner(owner).any { it.id == "id-41" }, "隐藏期间新MT仍须真实入库")
            assertTrue(paging.observed.all { it.second.isEmpty() })
            val other = StoredMessageConversation(owner, "12025550124")
            incomingId = "other-id"
            transport.emitReceived("engine-other", "+12025550124", "other synthetic", (epoch + 42 * 60000L) / 1000)
            driver.frame()
            driver.route(AppRoute.MessageThread(other.handle))
            assertEquals(other.handle.value, paging.selected)
            assertTrue(paging.observed.filter { it.first == other.handle }.flatMap { it.second }.all { it == "other-id" })
            driver.route(AppRoute.Messages)
            paging.observed.clear()
            driver.route(AppRoute.MessageThread(identity.handle))
            assertEquals(identity.handle.value, paging.selected)
            assertTrue(paging.observed.any { it.second.isNotEmpty() })
            assertTrue(paging.observed.flatMap { it.second }.none { it == "other-id" })
            assertEquals(1, paging.loads, "搜索/隐藏/返回不能重复准入旧R6请求")
            assertTrue(transport.requests.none { it.method == "sendSms" }, "浏览与分页不能发送短信")
        }
        } finally { release.complete(Unit); backend.close(); runCurrent(); stored.close() }
    } }

    /** 仅分页用例接生产源与测试transport；另两项提交生命周期用例仍使用原Source。 */
    private class PagingSource(private val backend: EngineMessageSource, val scheduler: TestCoroutineScheduler,
        val context: CoroutineContext) : MessageSource by backend {
        val ready get() = backend.state.value as MessageUiState.Ready
        var loads = 0
            private set
        var selected: String? = null
            private set
        val observed = mutableListOf<Pair<ConversationHandle?, List<String>>>()
        override fun selectConversation(handle: String?) { selected = handle; backend.selectConversation(handle) }
        override fun loadOlderMessages() { loads++; backend.loadOlderMessages() }
        override fun observeVisible(handle: ConversationHandle?, ids: List<String>) {
            observed.add(handle to ids); backend.observeVisible(handle, ids)
        }
    }

    private class Source : MessageSource {
        override val state = MutableStateFlow<MessageUiState>(MessageUiState.Ready(
            (20..39).map(::record), MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner,
            window = MessageThreadWindow(identity, MessageThreadCursor(identity, 20 * 60000L, "id-20")),
        ))
        var ready: MessageUiState.Ready
            get() = state.value as MessageUiState.Ready
            set(value) { state.value = value }
        var sends = 0
        var loads = 0
        var selected: String? = null
        val observed = mutableListOf<Pair<ConversationHandle?, List<String>>>()
        override fun refresh() = Unit
        override fun selectConversation(handle: String?) { selected = handle }
        override fun observeVisible(handle: ConversationHandle?, ids: List<String>) { observed.add(handle to ids) }
        override fun loadOlderMessages() { loads++; ready = ready.copy(window = ready.window?.copy(loading = true)) }
        override fun sendFromDisplayedOwner(owner: SimOwnerId?, number: String, text: String) {
            assertEquals(SmsProductionNavigationTest.owner, owner)
            sends++
            ready = ready.copy(sendBusy = true, sendAttempt = MessageSendAttempt(requireNotNull(owner), "attempt", number, text, OutgoingMessageStatus.QUEUED))
        }
    }

    private class Driver(initial: AppRoute.Subpage, private val paging: PagingSource? = null) : AutoCloseable {
        val source = Source()
        private val routes = MutableSharedFlow<AppRoute>(extraBufferCapacity = 1)
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val sources = SoftVoWiFiSources(
            FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
            FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
            paging ?: source, FixtureCallSource("unavailable"),
        )
        private var time = 0L
        private var activeRoute: AppRoute = initial
        private var searchOpen = false
        private val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES,
                    initialSubpage = initial, incomingRoutes = routes, forceDark = false)
            }
        }
        private val scene = if (paging == null) ImageComposeScene(width = 420, height = 900, content = content)
            else ImageComposeScene(width = 420, height = 900, coroutineContext = paging.context, content = content)
        init { frame() }
        fun frame() { repeat(16) {
            paging?.scheduler?.advanceTimeBy(100); paging?.scheduler?.runCurrent()
            time += 100_000_000L; scene.render(time).close()
            paging?.scheduler?.runCurrent()
        } }
        fun route(route: AppRoute) { runBlocking { routes.emit(route) }; activeRoute = route; searchOpen = false; frame() }
        fun fields() = nodes().filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            .takeLast(if (activeRoute == AppRoute.NewMessage || searchOpen) 2 else 1)
        fun fieldText(index: Int) = fields()[index].config[SemanticsProperties.EditableText].text
        fun edit(index: Int, text: String) {
            assertTrue(requireNotNull(fields()[index].config[SemanticsActions.SetText].action).invoke(AnnotatedString(text)))
            frame()
        }
        fun send() {
            val node = nodes().first { it.config.getOrNull(SemanticsProperties.StateDescription) == "可发送" }
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            frame()
        }
        fun complete(status: OutgoingMessageStatus = OutgoingMessageStatus.ACCEPTED) {
            source.ready = source.ready.copy(sendBusy = false, sendAttempt = source.ready.sendAttempt?.copy(status = status))
            frame()
        }
        fun releaseBusy() { source.ready = source.ready.copy(sendBusy = false); frame() }
        fun scrollToStart() {
            runBlocking { requireNotNull(nodes().last { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }.config[SemanticsActions.ScrollToIndex].action).invoke(0) }
            frame()
        }
        fun hasText(text: String) = nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        fun textTop(text: String) = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }.boundsInRoot.top
        fun click(text: String) {
            val node = nodes().first { node -> (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true) && node.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            if (text == "搜索会话") searchOpen = true
            frame()
        }
        private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            descend(owner.rootSemanticsNode)
        }
        override fun close() { scene.close(); paging?.scheduler?.runCurrent() }
    }

    companion object {
        private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        private const val peer = "+12025550123"
        private val identity = StoredMessageConversation(owner, peer.drop(1))
        private fun record(index: Int) = MessageRecord.Incoming("id-$index", peer, "synthetic message $index", index * 60000L)
    }
}
