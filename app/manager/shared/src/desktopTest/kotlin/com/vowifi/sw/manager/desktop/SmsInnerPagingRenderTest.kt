package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsInnerPagingRenderTest {
    private var time = 0L
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private fun record(index: Int) = MessageRecord.Incoming("id-$index", peer, "synthetic message $index", index * 60000L)

    @Test fun historySearchExitRestoresAnchorAndIncomingRequiresExplicitJump() = runBlocking {
        val state = mutableStateOf(MessageUiState.Ready((0..79).map(::record), MessageOrigin.ENGINE, ownerId = owner))
        val route = AppRoute.MessageThread(StoredMessageConversation(owner, peer.drop(1)).handle)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(width = 420, height = 900) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(false) {
                AppSubpageScene(route, appChromeSpec(AppearanceSettings(true, false), false), titleOverride = peer, onBack = {}) {
                    MessageThreadScreen(route.conversation, state.value, { _, _ -> error("read only") })
                }
            } }
        }
        try {
            frame(scene)
            assertTrue(nodes(scene).any { text(it, "synthetic message 79") })
            val list = nodes(scene).first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
            requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(21); frame(scene)
            val before = nodes(scene).first { text(it, "synthetic message 20") }.boundsInRoot.top
            click(scene, "搜索会话"); frame(scene)
            val field = nodes(scene).first { it.config.getOrNull(SemanticsActions.SetText) != null }
            requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString("MESSAGE 6")); frame(scene)
            assertTrue(nodes(scene).any { text(it, "仅搜索已加载消息 · 11 条") })
            state.value = state.value.copy(records = state.value.records + record(80))
            frame(scene)
            assertFalse(nodes(scene).any { text(it, "synthetic message 80") })
            click(scene, "关闭会话搜索"); frame(scene)
            val after = nodes(scene).first { text(it, "synthetic message 20") }.boundsInRoot.top
            assertTrue(abs(after - before) < 2f, "search exit moved anchor: $before -> $after")
            assertFalse(nodes(scene).any { text(it, "synthetic message 80") })
            click(scene, "查看新消息"); frame(scene)
            assertTrue(nodes(scene).any { text(it, "synthetic message 80") })
            state.value = state.value.copy(records = state.value.records + record(81)); frame(scene)
            assertTrue(nodes(scene).any { text(it, "synthetic message 81") })
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
    private fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    private fun click(scene: ImageComposeScene, value: String) {
        val node = nodes(scene).single { text(it, value) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()
    }
}
