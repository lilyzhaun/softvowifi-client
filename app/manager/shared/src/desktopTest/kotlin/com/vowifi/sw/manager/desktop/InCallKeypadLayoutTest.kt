package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.CallSessionClock
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class InCallKeypadLayoutTest {
    @Test fun largeKeysStayReachableAndAudioAndHangupRemainFixed() {
        for ((width, height) in listOf(390 to 844, 320 to 740)) {
            var time = 0L
            val digits = mutableListOf<Char>(); val actions = mutableListOf<CallAction>()
            var mute = 0; var speaker = 0
            val scene = ImageComposeScene(width = width, height = height) {
                SoftVoWiFiTheme(false) { CallScreenContent(CallUiState.Ready(CallPhase.InCall, CallAudioState.Active,
                    CallOrigin.DESKTOP_FIXTURE, binding = CallBinding("synthetic", "call", 1, "epoch"),
                    peer = CallPeer.parse("+12025550123"), dtmfSupported = true, clock = CallSessionClock(204000) { 0 }),
                    "SIM 1 · 合成线路", CallAudioControls(), onAction = actions::add, onMute = { mute++ },
                    onSpeaker = { speaker++ }, onDigit = digits::add, onRestoreAudio = {}, onMinimize = {}, ticking = false) }
            }
            fun frame() { repeat(40) { time += 16_000_000L; scene.render(time).close() } }
            fun button(label: String) = nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick) }
            fun click(label: String) { assertTrue(button(label).config[SemanticsActions.OnClick].action!!.invoke()); frame() }
            try {
                frame(); val hangup = button("挂断").boundsInRoot
                click("拨号键盘")
                val folder = Files.createDirectories(Path.of("build/reports/incall-keypad"))
                scene.render(time).use { image -> image.encodeToData()!!.use { Files.write(folder.resolve("expanded-$width.png"), it.bytes) } }
                val close = button("关闭键盘").boundsInRoot
                assertTrue(close.width <= 72f && close.height <= 72f, "关闭键盘应是同组圆形控件：$close")
                for (label in listOf("静音", "免提", "关闭键盘", "挂断") + "123456789*0#".map(Char::toString)) {
                    var bounds = button(label).boundsInRoot
                    if (label.length == 1 && bounds.height < 79f) {
                        val scroll = nodes(scene).single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy) }
                        assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f,200f));frame()
                        bounds = button(label).boundsInRoot
                    }
                    if (label.length == 1) { assertEquals(80f,bounds.width,.1f);assertEquals(80f,bounds.height,.1f) }
                    assertTrue(bounds.width >= 48f && bounds.height >= 48f, "$label 命中区域过小")
                    assertTrue(bounds.top >= 0 && bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width, "$label 被挤出标准视口：$bounds")
                }
                assertEquals(hangup.top, button("挂断").boundsInRoot.top, 2f, "挂断保持位置")
                click("静音"); click("免提"); click("5")
                assertEquals(1, mute); assertEquals(1, speaker); assertEquals(listOf('5'), digits); assertTrue(actions.isEmpty())
                click("关闭键盘"); click("拨号键盘")
                assertTrue(nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { it.text == "通话按键" })
                click("挂断"); assertEquals(listOf(CallAction.Hangup), actions)
            } finally { scene.close() }
        }
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
}
