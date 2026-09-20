package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
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
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsProductionNavigationTest {
    @Test fun pendingNewMessageDepartureReturnAndEditBackCannotLateClearDraft() {
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

    @Test fun pendingThreadDepartureReturnCannotLateClearNewerDraft() {
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

    @Test fun earlierPageFinishesDuringSearchAndHiddenRouteDoesNotObserveIncoming() {
        Driver(AppRoute.MessageThread(identity.handle)).use { driver ->
            driver.scrollToStart()
            driver.click("加载更早消息")
            assertEquals(1, driver.source.loads)
            driver.source.observed.clear()
            driver.click("搜索会话")
            driver.edit(0, "message 25")
            assertTrue(driver.hasText("仅搜索已加载消息 · 1 条"), "search field must filter the production thread")
            assertTrue(driver.source.observed.isNotEmpty(), "production visibility binder must be active")
            assertTrue(driver.source.observed.any { it.second.contains("id-25") })
            driver.source.observed.clear()
            driver.source.ready = driver.source.ready.copy(
                records = (0..40).map(::record),
                window = MessageThreadWindow(identity, null),
            )
            driver.frame()
            assertTrue(driver.source.observed.all { (_, ids) -> ids.all { it == "id-25" } })
            driver.route(AppRoute.Messages)
            driver.source.observed.clear()
            driver.source.ready = driver.source.ready.copy(records = (0..41).map(::record))
            driver.frame()
            assertTrue(driver.source.observed.all { it.second.isEmpty() })
            val other = StoredMessageConversation(owner, "12025550124")
            driver.source.ready = driver.source.ready.copy(
                records = listOf(MessageRecord.Incoming("other-id", "+12025550124", "other synthetic", 0)),
                window = MessageThreadWindow(other, null),
            )
            driver.route(AppRoute.MessageThread(other.handle))
            assertEquals(other.handle.value, driver.source.selected)
            assertTrue(driver.source.observed.filter { it.first == other.handle }.flatMap { it.second }.all { it == "other-id" })
            driver.route(AppRoute.Messages)
            driver.source.ready = driver.source.ready.copy(records = (0..41).map(::record), window = MessageThreadWindow(identity, null))
            driver.source.observed.clear()
            driver.route(AppRoute.MessageThread(identity.handle))
            assertEquals(identity.handle.value, driver.source.selected)
            assertTrue(driver.source.observed.any { it.second.isNotEmpty() })
            assertTrue(driver.source.observed.flatMap { it.second }.none { it == "other-id" })
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

    private class Driver(initial: AppRoute.Subpage) : AutoCloseable {
        val source = Source()
        private val routes = MutableSharedFlow<AppRoute>(extraBufferCapacity = 1)
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val sources = SoftVoWiFiSources(
            FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
            FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
            source, FixtureCallSource("unavailable"),
        )
        private var time = 0L
        private var activeRoute: AppRoute = initial
        private var searchOpen = false
        private val scene = ImageComposeScene(width = 420, height = 900) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES,
                    initialSubpage = initial, incomingRoutes = routes, forceDark = false)
            }
        }
        init { frame() }
        fun frame() { repeat(16) { time += 100_000_000L; scene.render(time).close() } }
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
        fun complete() {
            source.ready = source.ready.copy(sendBusy = false, sendAttempt = source.ready.sendAttempt?.copy(status = OutgoingMessageStatus.ACCEPTED))
            frame()
        }
        fun releaseBusy() { source.ready = source.ready.copy(sendBusy = false); frame() }
        fun scrollToStart() {
            runBlocking { requireNotNull(nodes().last { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }.config[SemanticsActions.ScrollToIndex].action).invoke(0) }
            frame()
        }
        fun hasText(text: String) = nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        fun click(text: String) {
            val node = nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && node.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            if (text == "搜索会话") searchOpen = true
            frame()
        }
        private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            descend(owner.rootSemanticsNode)
        }
        override fun close() = scene.close()
    }

    companion object {
        private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        private const val peer = "+12025550123"
        private val identity = StoredMessageConversation(owner, peer.drop(1))
        private fun record(index: Int) = MessageRecord.Incoming("id-$index", peer, "synthetic message $index", index * 60000L)
    }
}
