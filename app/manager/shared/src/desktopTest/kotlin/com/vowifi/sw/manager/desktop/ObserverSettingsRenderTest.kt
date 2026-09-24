package com.vowifi.sw.manager.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult
import com.vowifi.sw.manager.data.CallNotificationSettingsSource
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ObserverSettingsRenderTest {
    @Test fun realSettingsRowsRenderAndObserverClickRoutesExactlyOnce() {
        for (status in listOf(ObserverChannelStatus.ENABLED, ObserverChannelStatus.BLOCKED, ObserverChannelStatus.MISSING)) {
            val source = object : CallNotificationSettingsSource {
                override val state = MutableStateFlow(CallNotificationSettingsSnapshot(
                    NotificationPermissionStatus.GRANTED, FullScreenStatus.ALLOWED,
                    channel = IncomingCallChannelStatus.HIGH, observerChannel = status))
                val actions = mutableListOf<SettingsAction>()
                override fun refresh() = Unit
                override fun openSettings(action: SettingsAction): CallNotificationSettingsLaunchResult {
                    actions += action
                    return CallNotificationSettingsLaunchResult.UNSUPPORTED
                }
            }
            val scene = ImageComposeScene(width = 390, height = 844) {
                SoftVoWiFiTheme(forceDark = false) {
                    AppSubpageScene(
                        route = AppRoute.SettingsCallNotifications,
                        chromeSpec = appChromeSpec(AppearanceSettings.Default, blurSupported = false),
                        onBack = {},
                    ) { SettingsCallNotificationsScreen(source) }
                }
            }
            try {
                repeat(12) { scene.render(it * 100_000_000L).close() }
                val nodes = scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }
                val header = nodes.single {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "通知设置" } == true
                }
                assertTrue(header.boundsInRoot.top >= 0f)
                assertTrue(header.boundsInRoot.bottom < 120f)
                scene.render(1_200_000_000L).use { image -> requireNotNull(image.encodeToData()).use {
                    Files.write(Path.of("/tmp/opencode/issue27-native-settings-${status.name.lowercase()}.png"), it.bytes)
                } }
                val row = scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }.single {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "后台常驻通知" } == true &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                assertTrue(requireNotNull(row.config[SemanticsActions.OnClick].action).invoke())
                assertEquals(listOf<SettingsAction>(SettingsAction.ManageObserverChannel), source.actions)
            } finally { scene.close() }
        }
    }

    private fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
}
