package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.ui.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class DialerFocusLifecycleRenderTest {
    private var time = 0L

    @Test fun retainedInactiveDialpadClearsNativeFocusWithoutDiscardingNumber() = smsOnEdt {
        var active by mutableStateOf(true)
        var number by mutableStateOf("123")
        val scene = ImageComposeScene(width = 390, height = 844) {
            CompositionLocalProvider(LocalAppRouteActive provides active) {
                SoftVoWiFiTheme(false) { PhoneDialerScreen(number, { number = it }, "合成测试线路", false, false, null, {}, {}, {}) }
            }
        }
        try {
            frame(scene)
            assertTrue(requireNotNull(field(scene).config[SemanticsActions.RequestFocus].action).invoke())
            frame(scene)
            assertEquals(true, field(scene).config.getOrNull(SemanticsProperties.Focused))
            active = false
            frame(scene)
            assertTrue(field(scene).config.contains(SemanticsProperties.Disabled))
            assertNotEquals(true, field(scene).config.getOrNull(SemanticsProperties.Focused), "失活字段不得声明持有焦点")
            assertEquals("123", number, "清焦点不能清除线路草稿")
            active = true; frame(scene)
            assertEquals(false, field(scene).config.getOrNull(SemanticsProperties.Focused), "重新启用后原生焦点必须已释放，而非只隐藏语义")
            assertEquals("123", number)
        } finally { scene.close() }
    }

    @Test fun activeDialpadKeepsFocusThroughEditingAndBusyDoesNotAuthorizeDial() = smsOnEdt {
        var number by mutableStateOf("123")
        var busy by mutableStateOf(false)
        var dials = 0
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { PhoneDialerScreen(number, { number = it }, "合成测试线路", false, busy, null, { dials++ }, {}, {}) }
        }
        try {
            frame(scene)
            requireNotNull(field(scene).config[SemanticsActions.RequestFocus].action).invoke()
            frame(scene)
            requireNotNull(field(scene).config[SemanticsActions.SetText].action).invoke(AnnotatedString("1234"))
            frame(scene)
            assertEquals("1234", number)
            assertEquals(true, field(scene).config.getOrNull(SemanticsProperties.Focused))
            busy = true; frame(scene)
            val action = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("正在准备") == true }
            assertTrue(action.config.contains(SemanticsProperties.Disabled))
            assertEquals(0, dials)
            assertEquals("1234", number)
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun field(scene: ImageComposeScene) = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("电话号码") == true }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
}
