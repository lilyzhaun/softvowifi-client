package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.ui.AnimatedBottomNavigationBar
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.AppearanceSettings
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import com.vowifi.sw.manager.ui.appChromeSpec
import com.vowifi.sw.manager.ui.NativeFloatingNavigation
import com.vowifi.sw.manager.ui.DestinationNavigationItems
import com.vowifi.sw.manager.ui.SettingsAppearanceScreen
import com.vowifi.sw.manager.ui.AppearanceSettingsActions
import com.vowifi.sw.manager.ui.LocalMessageUnreadSummary
import com.vowifi.sw.manager.model.MessageUnreadSummary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class NativeNavigationRenderTest {
    @Test fun floatingTabsRetainOrderSelectionCallbacksAndUnmounting() = renderNavigation(true)
    @Test fun standardTabsRetainOrderSelectionCallbacksAndUnmounting() = renderNavigation(false)

    @Test fun retiredLiquidPreferenceCannotActivateRemovedRenderer() {
        assertEquals(
            appChromeSpec(AppearanceSettings.Default.copy(liquidGlassRequested = false), blurSupported = true),
            appChromeSpec(AppearanceSettings.Default.copy(liquidGlassRequested = true), blurSupported = true),
        )
    }

    @Test fun legacyLiquidPreferenceIsReadOnlyInformationNotAToggle() {
        var callbacks = 0
        val action: (Boolean) -> Unit = { callbacks++ }
        val scene = ImageComposeScene(width = 375, height = 1000) {
            SoftVoWiFiTheme(forceDark = false) {
                SettingsAppearanceScreen(
                    AppearanceSettings.Default.copy(liquidGlassRequested = true),
                    blurSupported = true,
                    actions = AppearanceSettingsActions(action, action, action, action, action),
                )
            }
        }
        try {
            repeat(6) { scene.render((it + 1) * 100_000_000L).close() }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            assertEquals(4, nodes.count { it.config.getOrNull(SemanticsProperties.ToggleableState) != null })
            val explanation = nodes.single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "已改用原生静态导航，不再提供液态折射" } == true
            }
            assertEquals(null, explanation.config.getOrNull(SemanticsActions.OnClick))
            assertEquals(0, callbacks)
        } finally { scene.close() }
    }

    @Test fun floatingNavigationConsumesBottomAndHorizontalInsetsExactlyOnce() {
        val scene = ImageComposeScene(width = 375, height = 240) {
            SoftVoWiFiTheme(forceDark = true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    NativeFloatingNavigation(navigationInsets = WindowInsets(left = 20, right = 16, bottom = 24)) {
                        DestinationNavigationItems(AppDestination.MESSAGES) {}
                    }
                }
            }
        }
        try {
            repeat(4) { scene.render((it + 1) * 100_000_000L).close() }
            val items = tabs(scene)
            assertEquals(4, items.size)
            items.forEach {
                assertEquals(204f, it.boundsInRoot.bottom)
                assertEquals(64f, it.boundsInRoot.height)
                assertTrue(it.boundsInRoot.left >= 32f)
                assertTrue(it.boundsInRoot.right <= 347f)
            }
        } finally { scene.close() }
    }

    private fun renderNavigation(floating: Boolean) {
        val selected = mutableStateOf(AppDestination.HOME)
        val visible = mutableStateOf(true)
        val unread = mutableStateOf<MessageUnreadSummary>(MessageUnreadSummary.Ready(0))
        val callbacks = mutableListOf<AppDestination>()
        val scene = ImageComposeScene(width = 375, height = 240) {
            SoftVoWiFiTheme(forceDark = false) {
                CompositionLocalProvider(LocalMessageUnreadSummary provides unread.value) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    AnimatedBottomNavigationBar(
                        visible = visible.value,
                        destination = selected.value,
                        onDestinationSelected = { callbacks += it; selected.value = it },
                        chromeSpec = appChromeSpec(AppearanceSettings.Default.copy(floatingNavigation = floating), false),
                        backdrop = null,
                    )
                }
                }
            }
        }
        var time = 0L
        fun frame() { repeat(4) { time += 100_000_000L; scene.render(time).close() } }
        try {
            frame()
            val initial = tabs(scene)
            assertEquals(4, initial.size)
            assertEquals(listOf("首页", "通话", "短信", "设置"), initial.map(::label))
            initial.forEach { tab ->
                assertTrue(tab.boundsInRoot.width >= 48f)
                assertTrue(tab.boundsInRoot.height >= 48f)
                assertTrue(tab.boundsInRoot.left >= 0f && tab.boundsInRoot.right <= 375f)
            }
            if (floating) assertEquals(228f, initial.first().boundsInRoot.bottom)
            for (destination in AppDestination.entries.drop(1)) {
                val tab = tabs(scene).single { label(it) == destination.label }
                assertTrue(requireNotNull(tab.config[SemanticsActions.OnClick].action).invoke())
                frame()
                assertEquals(destination, callbacks.last())
                assertEquals(destination.label, label(tabs(scene).single { it.config[SemanticsProperties.Selected] }))
            }
            assertEquals(AppDestination.entries.drop(1), callbacks)
            selected.value = AppDestination.HOME
            frame()
            assertEquals("首页", label(tabs(scene).single { it.config[SemanticsProperties.Selected] }))
            assertEquals(3, callbacks.size)
            if (floating) {
                val selectedTab = tabs(scene).single { it.config[SemanticsProperties.Selected] }
                assertTrue(requireNotNull(selectedTab.config[SemanticsActions.OnClick].action).invoke())
                frame()
                assertEquals(3, callbacks.size)
            }
            unread.value = MessageUnreadSummary.Ready(105)
            frame()
            val unreadTabs = tabs(scene).filter { tab ->
                descendants(tab).any { "105 条未读短信" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            }
            assertEquals(listOf("短信"), unreadTabs.map(::label))
            unread.value = MessageUnreadSummary.Ready(0)
            frame()
            assertFalse(tabs(scene).any { tab ->
                descendants(tab).any { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("未读短信") } }
            })
            val output = File("build/reports/native-navigation-${if (floating) "floating" else "normal"}.png")
            output.parentFile.mkdirs()
            scene.render(time + 100_000_000L).use { image ->
                requireNotNull(image.encodeToData()).use { output.writeBytes(it.bytes) }
            }
            visible.value = false
            frame()
            assertTrue(tabs(scene).isEmpty())
        } finally {
            scene.close()
        }
    }

    private fun tabs(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun label(node: SemanticsNode): String = descendants(node)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        .joinToString("") { it.text }
}
