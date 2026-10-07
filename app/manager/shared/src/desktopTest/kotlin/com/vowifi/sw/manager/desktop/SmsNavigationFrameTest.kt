package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SmsNavigationFrameTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private val handle = StoredMessageConversation(owner, peer.drop(1)).handle
    private val rootNotice = "合成根页：历史线路只读"
    private val threadNotice = "合成线程：此历史线路未连接"
    private val body = "只在合成线程显示的正文"
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @Test fun enteringThreadCannotReplaceTheRetainedRootWithThreadLoadingOrNotice() = runTest { smsOnEdt {
        var state: MessageUiState by mutableStateOf(root())
        var navigation by mutableStateOf(AppNavigationState.initial(AppRoute.Messages))
        val scene = scene(testScheduler, { state }, { navigation }) {
            state = root(); navigation = AppNavigator.back(navigation)
        }
        var time = 0L
        fun frame() { testScheduler.advanceTimeBy(16); testScheduler.runCurrent(); time += 16_000_000L; scene.render(time).close() }
        try {
            repeat(24) { frame() }
            assertTrue(hasText(scene, rootNotice))
            state = MessageUiState.Loading
            navigation = AppNavigator.push(navigation, AppRoute.MessageThread(handle))
            repeat(4) { frame(); assertTrue(hasText(scene, rootNotice), "进入期间根页不得改成线程的Loading") }
            state = thread()
            repeat(20) {
                frame()
                assertTrue(hasText(scene, rootNotice), "根页只读文案不能被线程notice替换")
                assertFalse(hasText(scene, threadNotice, "sms-root"), "线程attachmentNotice不应闪入根列表")
            }
            assertTrue(hasText(scene, body))
        } finally { scene.close() }
    } }

    @Test fun poppingThreadKeepsItsBodyAndTitleUntilExitInsteadOfAReadOnlyPlaceholder() = runTest { smsOnEdt {
        var state: MessageUiState by mutableStateOf(root())
        var navigation by mutableStateOf(AppNavigationState.initial(AppRoute.Messages))
        val scene = scene(testScheduler, { state }, { navigation }) {
            state = root(); navigation = AppNavigator.back(navigation)
        }
        var time = 0L
        fun frame() { testScheduler.advanceTimeBy(16); testScheduler.runCurrent(); time += 16_000_000L; scene.render(time).close() }
        try {
            repeat(24) { frame() }
            state = thread(); navigation = AppNavigator.push(navigation, AppRoute.MessageThread(handle))
            repeat(32) { frame() }
            assertTrue(hasText(scene, body))
            assertTrue(hasHeading(scene, peer))
            state = root(); navigation = AppNavigator.back(navigation)
            repeat(8) {
                frame()
                assertTrue(hasText(scene, body, "sms-thread"), "退场线程不能被根Inbox状态改成缺会话占位")
                assertTrue(hasHeading(scene, peer), "线程标题与正文一起退场，不能闪回根页标题")
                assertFalse(hasText(scene, "消息只读"), "退场中不得闪出大只读图标/标题")
                assertFalse(hasText(scene, "会话不可用"))
            }
            repeat(32) { frame() }
            scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { png ->
                java.nio.file.Files.write(java.nio.file.Path.of("/tmp/opencode/sms-nav-exit-probe.png"), png.bytes)
            } }
            assertFalse(hasText(scene, body, "sms-thread", visibleOnly = true), "退出后仍有可见线程正文")
            assertTrue(hasText(scene, rootNotice))
        } finally { scene.close() }
    } }

    @Test fun aCurrentThreadStillShowsRealLoadingAndStorageFailure() = runTest { smsOnEdt {
        var state: MessageUiState by mutableStateOf(thread())
        val navigation = AppNavigator.push(AppNavigationState.initial(AppRoute.Messages), AppRoute.MessageThread(handle))
        val scene = scene(testScheduler, { state }, { navigation }, {})
        var time = 0L
        fun frame() { testScheduler.advanceTimeBy(16); testScheduler.runCurrent(); time += 16_000_000L; scene.render(time).close() }
        try {
            repeat(32) { frame() }; assertTrue(hasText(scene, body))
            state = MessageUiState.Loading; repeat(4) { frame() }
            assertTrue(hasText(scene, "正在读取消息")); assertFalse(hasText(scene, body, "sms-thread"))
            state = MessageUiState.Failure("合成存储失败"); repeat(4) { frame() }
            assertTrue(hasText(scene, "消息读取失败")); assertFalse(hasText(scene, body, "sms-thread"))
        } finally { scene.close() }
    } }

    private fun scene(scheduler: TestCoroutineScheduler, state: () -> MessageUiState,
        navigation: () -> AppNavigationState, back: () -> Unit) = ImageComposeScene(width = 390, height = 844,
        coroutineContext = StandardTestDispatcher(scheduler)) {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(false) {
            AppShell(navigation(), { back() }, topBarTitleOverride = messageRouteTopBarTitle(navigation().topRoute, state()),
                appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                when (route) {
                    AppRoute.Messages -> Box(Modifier.fillMaxSize().semantics { testTag = "sms-root" }) { MessagesScreen(state(), {}, {}) }
                    is AppRoute.MessageThread -> Box(Modifier.fillMaxSize().semantics { testTag = "sms-thread" }) {
                        MessageThreadScreen(route.conversation, state(), { _, _ -> error("must not send") })
                    }
                    else -> Unit
                }
            }
        } }
    }

    private fun root(): MessageUiState = MessageUiState.Inbox(owner, MessageInboxPage(items = listOf(
        MessageConversationSummary(StoredMessageConversation(owner, peer.drop(1)), peer, "合成列表预览", "one", 1, null, 1, 0),
    ), loading = false), composerEnabled = false, attachmentNotice = rootNotice)
    private fun thread() = MessageUiState.Ready(listOf(MessageRecord.Incoming("one", peer, body, 1)),
        MessageOrigin.ENGINE, ownerId = owner, composerEnabled = false, attachmentNotice = threadNotice)
    private fun hasText(scene: ImageComposeScene, text: String, tag: String? = null,
        visibleOnly: Boolean = false) = scene.semanticsOwners.any { owner ->
        fun has(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
            (!visibleOnly || node.boundsInRoot.let { it.width > 0 && it.height > 0 && it.right > 0 && it.left < 390 && it.bottom > 0 && it.top < 844 }) || node.children.any(::has)
        fun matching(node: SemanticsNode): Boolean = if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) has(node) else node.children.any(::matching)
        // Inspect retained page content even when it is intentionally hidden from accessibility.
        if (tag == null) has(owner.unmergedRootSemanticsNode) else matching(owner.unmergedRootSemanticsNode)
    }
    private fun hasHeading(scene: ImageComposeScene, text: String) = scene.semanticsOwners.any { owner ->
        fun has(node: SemanticsNode): Boolean = node.config.contains(SemanticsProperties.Heading) &&
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || node.children.any(::has)
        has(owner.rootSemanticsNode)
    }
}
