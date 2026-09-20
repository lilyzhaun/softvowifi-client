package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagePagingRenderTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private var time = 0L
    private val scheduler = TestCoroutineScheduler()

    @Test fun initialPagingDoesNotDependOnAnIdleUnconfinedEventLoop() = runBlocking(Dispatchers.Unconfined) {
        MessagePagingRenderTest().olderActionPrependsAndKeepsVisibleMessageInsteadOfJumpingToNewest()
    }

    @Test fun rootLoadsMoreAndRunsRealHistorySearchFromInput() = runBlocking {
        val repository = FakeMessageRepository()
        repeat(121) { repository.insertIncomingIfAbsent(owner, "id-$it", "engine-$it", "1555$it", if (it == 0) "needle" else "synthetic", it.toLong(), 1) }
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val selection = MessageHistorySelection(repository, scope, MessagePersistenceObserver { _, _, _ -> })
        selection.refreshInbox()
        val scene = ImageComposeScene(width = 420, height = 900) {
            SoftVoWiFiTheme(forceDark = false) { Scaffold(Modifier.fillMaxSize()) {
                MessagesScreen(selection.state.collectAsState().value, {}, {},
                    historyChoices = selection.historyChoices.collectAsState().value,
                    onSearchHistory = selection::searchInbox, onLoadMore = selection::loadMoreInbox)
            } }
        }
        try {
            frame(scene)
            scroll(scene, 53)
            frame(scene)
            click(scene, "加载更多会话")
            frame(scene)
            assertEquals(100, (selection.state.value as MessageUiState.Inbox).page.items.size)
            scroll(scene, 0); frame(scene)
            val search = nodes(scene).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            requireNotNull(search.config[SemanticsActions.SetText].action).invoke(AnnotatedString("NEEDLE"))
            frame(scene)
            assertEquals(listOf("needle"), (selection.state.value as MessageUiState.Inbox).page.items.map { it.latestPreview })
            assertTrue(nodes(scene).any { text(it, "needle") })
            screenshot(scene, "issue11-paged-search.png")
        } finally { scene.close(); selection.close(); scope.cancel() }
    }

    @Test fun olderActionPrependsAndKeepsVisibleMessageInsteadOfJumpingToNewest() = runBlocking {
        val repository = FakeMessageRepository()
        repeat(121) { repository.insertIncomingIfAbsent(owner, "id-$it", "engine-$it", "1555", "synthetic $it", it.toLong(), 1) }
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val selection = MessageHistorySelection(repository, scope, MessagePersistenceObserver { _, _, _ -> })
        val identity = StoredMessageConversation(owner, "1555")
        selection.select(identity.handle.value)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(width = 420, height = 900) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SoftVoWiFiTheme(forceDark = false) { Scaffold(Modifier.fillMaxSize()) {
                    MessageThreadScreen(identity.handle, selection.state.collectAsState().value, { _, _ -> }, onLoadOlder = selection::loadOlder)
                } }
            }
        }
        try {
            frame(scene)
            assertTrue(nodes(scene).any { text(it, "synthetic 120") })
            scroll(scene, 0); frame(scene)
            val before = nodes(scene).first { text(it, "synthetic 71") }.boundsInRoot.top
            click(scene, "加载更早消息"); frame(scene)
            assertEquals(100, (selection.state.value as MessageUiState.Ready).records.size)
            val after = nodes(scene).first { text(it, "synthetic 71") }.boundsInRoot.top
            assertTrue(kotlin.math.abs(after - before) < 2f, "older prepend moved visible anchor: $before -> $after")
            assertTrue(nodes(scene).none { text(it, "synthetic 120") })
            screenshot(scene, "issue11-paged-thread.png")
        } finally { scene.close(); selection.close(); scope.cancel() }
    }

    private fun frame(scene: ImageComposeScene) { repeat(12) { scheduler.runCurrent(); time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
    private fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    private fun click(scene: ImageComposeScene, value: String) {
        val node = nodes(scene).single { text(it, value) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private suspend fun scroll(scene: ImageComposeScene, index: Int) {
        val node = nodes(scene).first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
        assertTrue(requireNotNull(node.config[SemanticsActions.ScrollToIndex].action).invoke(index))
    }
    private fun screenshot(scene: ImageComposeScene, name: String) {
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data -> Files.write(Path.of("/tmp/opencode", name), data.bytes) } }
    }
}
