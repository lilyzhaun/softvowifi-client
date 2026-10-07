package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SmsConversationMenuTest {
    @Test fun longPressHasOnlyUnreadAndDeleteAndCancellationDoesNotDelete() = smsOnEdt {
        val scheduler = TestCoroutineScheduler()
        val owner = SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43))!!
        val identity = StoredMessageConversation(owner, "155501")
        val calls = mutableListOf<MessageConversationAction>()
        var opens = 0
        var active by mutableStateOf(true)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(false) {
            AppShell(AppNavigationState.initial(AppRoute.Messages), {}, appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
            if (route == AppRoute.Messages) CompositionLocalProvider(LocalAppRouteActive provides active) {
                MessagesScreen(MessageUiState.Ready(listOf(MessageRecord.Incoming("id", "155501", "synthetic", 1, isRead = true)),
                    MessageOrigin.ENGINE, ownerId = owner), { opens++ }, {}, onConversationAction = { handle, action ->
                    assertEquals(identity.handle, handle); calls += action; MessageConversationActionResult.APPLIED
                })
            } } } }
        }
        var time = 0L
        fun frame() { repeat(40) { scheduler.advanceTimeBy(16); scheduler.runCurrent(); time += 16_000_000L; scene.render(time).close(); scheduler.runCurrent() } }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
            tree(owner.rootSemanticsNode)
        }
        fun text(n: SemanticsNode, label: String): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || n.children.any { text(it, label) }
        fun click(label: String) {
            val found = nodes().filter { it.config.contains(SemanticsActions.OnClick) && text(it, label) }
            requireNotNull(found.lastOrNull()).config[SemanticsActions.OnClick].action!!.invoke(); frame()
        }
        fun openMenu() {
            val row = nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作" }
            row.config[SemanticsActions.OnLongClick].action!!.invoke(); frame()
            assertEquals(0, opens)
            assertTrue(nodes().any { text(it, "标为未读") })
            assertTrue(nodes().any { text(it, "删除") })
        }
        try {
            frame(); openMenu(); click("标为未读")
            assertEquals(listOf(MessageConversationAction.MARK_UNREAD), calls)
            openMenu(); click("删除"); assertTrue(nodes().any { text(it, "删除会话？") },
                "确认框缺失，合成文本：" + nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text })
            click("取消"); assertEquals(1, calls.size)
            openMenu(); click("删除"); click("删除")
            assertEquals(listOf(MessageConversationAction.MARK_UNREAD, MessageConversationAction.DELETE), calls)
            val stale = nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作" }
                .config[SemanticsActions.OnLongClick].action!!
            active = false; frame(); stale(); frame()
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" })
            assertEquals(2, calls.size)
        } finally { scene.close(); scheduler.runCurrent() }
    }
}
