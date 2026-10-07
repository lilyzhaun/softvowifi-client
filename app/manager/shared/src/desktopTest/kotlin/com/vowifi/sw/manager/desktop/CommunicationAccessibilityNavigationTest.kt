package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunicationAccessibilityNavigationTest {
    @Test fun coveredCallsRootDoesNotExposeActionsAndReturnsAfterPop() = rootSemanticsFollowNavigation(AppRoute.Calls)

    @Test fun coveredMessagesRootDoesNotExposeActionsAndReturnsAfterPop() = rootSemanticsFollowNavigation(AppRoute.Messages)

    private fun rootSemanticsFollowNavigation(root: AppRoute.Root) = runTest { smsOnEdt {
        var navigation by mutableStateOf(AppNavigationState.initial(root))
        var draft by mutableStateOf("1202")
        var opens = 0
        var businessActions = 0
        var rootDisposals = 0
        val child = if (root == AppRoute.Calls) AppRoute.Dialpad else AppRoute.NewMessage
        val rootAction = if (root == AppRoute.Calls) "拨号盘" else "新建短信"
        val childField = if (root == AppRoute.Calls) "电话号码" else "输入电话号码"
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val open = { opens++; navigation = AppNavigator.push(navigation, child) }
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = StandardTestDispatcher(testScheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(false) {
                AppShell(navigation, { navigation = it }, appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                    if (route == root) DisposableEffect(Unit) { onDispose { rootDisposals++ } }
                    when (route) {
                        AppRoute.Calls -> CallsScreen(CallUiState.Unavailable("合成离线状态"), true, {}, CallHistoryState.Empty,
                             open, {}, {}, {}, {}, hasRegisteredEngine = true)
                         AppRoute.Messages -> MessagesScreen(MessageUiState.Empty, {}, open, hasRegisteredEngine = true)
                        AppRoute.Dialpad -> CallsDialpadScreen(CallUiState.Unavailable("合成离线状态"),
                            draft, { draft = it }, { businessActions++ }, outgoingEnabled = false)
                        AppRoute.NewMessage -> NewMessageScreen(MessageUiState.Empty, draft, "", { draft = it }, {}, {},
                            { _, _ -> businessActions++ })
                        else -> Unit
                    }
                }
            } }
        }
        var time = 0L
        fun frames(count: Int) { repeat(count) {
            testScheduler.advanceTimeBy(16); testScheduler.runCurrent()
            time += 16_000_000L; scene.render(time).close()
        } }
        fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
            tree(owner.rootSemanticsNode)
        }
        fun named(label: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
        fun click(label: String) {
            val target = named(label).single { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
        }
        try {
            frames(40); click(rootAction); frames(40)
            assertEquals(child, navigation.topRoute)
            assertEquals(1, opens)
            assertTrue(named(childField).isNotEmpty(), "当前子页输入必须可供辅助服务读取")
            assertTrue(named(rootAction).isEmpty(), "被子页覆盖的根操作不得残留在服务可见语义树中")
            assertEquals(0, rootDisposals, "只隔离辅助语义，保留根页实例及预测返回画面")
            click("返回"); frames(40)
            assertEquals(root, navigation.topRoute)
            assertEquals(1, named(rootAction).size, "返回后根操作应恢复且不重复")
            assertTrue(named(childField).isEmpty())
            click(rootAction); frames(40)
            assertEquals(2, opens)
            assertEquals("1202", draft, "隐藏根语义不能清除子页草稿")
            assertTrue(named(rootAction).isEmpty())
            assertEquals(0, rootDisposals)
            assertEquals(0, businessActions)
        } finally { scene.close() }
    } }
}
