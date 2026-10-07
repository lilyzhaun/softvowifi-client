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
class PhoneNavigationExitTest {
    @Test fun realNavigationPopDisablesTheRetainedDialerBeforeItLeavesTheScreen() = runTest { smsOnEdt {
        var navigation by mutableStateOf(AppNavigator.push(AppNavigationState.initial(AppRoute.Calls), AppRoute.Dialpad))
        var draft by mutableStateOf("1202")
        var dials = 0
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = StandardTestDispatcher(testScheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(false) {
                AppShell(navigation, { navigation = it }, appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                    when (route) {
                        AppRoute.Calls -> CallsScreen(CallUiState.Unavailable("合成离线状态"), true, {}, CallHistoryState.Empty,
                            { navigation = AppNavigator.push(navigation, AppRoute.Dialpad) }, {}, {}, {}, {})
                        AppRoute.Dialpad -> CallsDialpadScreen(CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE),
                            draft, { draft = it }, { dials++ }, outgoingEnabled = true)
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
        fun named(label: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
        try {
            frames(40)
            assertFalse(assertNotNull(named("电话号码")).config.contains(SemanticsProperties.Disabled))
            val back = assertNotNull(named("返回"))
            requireNotNull(back.config[SemanticsActions.OnClick].action).invoke()
            frames(2)
            assertEquals(AppRoute.Calls, navigation.topRoute)
            val retained = assertNotNull(named("电话号码"), "前置：真实退场动画必须仍保留拨号页")
            assertTrue(retained.config.contains(SemanticsProperties.Disabled), "真实Nav3退场条目必须收到最新routeActive")
            assertTrue(assertNotNull(named("拨打")).config.contains(SemanticsProperties.Disabled))
            frames(40)
            assertNull(named("电话号码"))
            navigation = AppNavigator.push(navigation, AppRoute.Dialpad); frames(40)
            val restored = assertNotNull(named("电话号码"))
            assertFalse(restored.config.contains(SemanticsProperties.Disabled))
            assertEquals("1202", draft)
            assertEquals(0, dials)
        } finally { scene.close() }
    } }
}
