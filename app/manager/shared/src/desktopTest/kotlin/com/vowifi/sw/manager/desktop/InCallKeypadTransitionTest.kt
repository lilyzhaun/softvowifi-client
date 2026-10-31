package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.CallSessionClock
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.captureTestDiagnostics
import com.vowifi.sw.manager.ui.testing.diagnosticCaptureDirectory
import java.nio.file.Files
import org.jetbrains.skia.Bitmap
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class InCallKeypadTransitionTest {
    private var time = 0L
    private fun state() = CallUiState.Ready(CallPhase.InCall, CallAudioState.Active, CallOrigin.DESKTOP_FIXTURE,
        binding = CallBinding("synthetic", "call", 1, "epoch"), peer = CallPeer.parse("+12025550123"),
        dtmfSupported = true, clock = CallSessionClock(204000) { 0 })

    @Test fun eightyDpKeysAndStableChromeThroughCompleteAndInterruptedTransitions() {
        val state = state();val tones = mutableListOf<Char>();val actions = mutableListOf<CallAction>()
        val visibility = mutableListOf<Boolean>()
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { CallScreenContent(state, "SIM 1 · 合成线路", CallAudioControls(),
                onAction = actions::add, onMute = {}, onSpeaker = {}, onDigit = tones::add, onRestoreAudio = {},
                onMinimize = {}, onKeypad = visibility::add, ticking = false) }
        }
        val folder = diagnosticCaptureDirectory("incall-continuous")
        captureTestDiagnostics { Files.createDirectories(folder) }
        try {
            frame(scene, 50)
            val original = listOf("挂断", "静音", "免提").associateWith { button(scene, it).boundsInRoot }
            val caller = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { s -> s.text == "+1 202 555 0123" } == true }.boundsInRoot
            val positions = mutableListOf<Map<String, androidx.compose.ui.geometry.Rect>>()
            var index = 0
            fun transitionFrames(count: Int) { repeat(count) {
                frame(scene, 1)
                positions += original.keys.associateWith { button(scene, it).boundsInRoot }
                scene.render(time).use { image ->
                    captureTestDiagnostics { image.encodeToData()!!.use { png ->
                        Files.write(folder.resolve("frame-${index++.toString().padStart(3,'0')}.png"), png.bytes)
                    } }
                }
            } }
            click(scene, "拨号键盘");transitionFrames(50)
            val key = button(scene, "1").boundsInRoot
            assertEquals(80f, key.width, .1f, "通话键不得缩成56dp")
            assertEquals(80f, key.height, .1f)
            val two = button(scene,"2").boundsInRoot
            val letterInk = scene.render(time).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                var ink = 0
                for (y in two.bottom.toInt()-26 until two.bottom.toInt()-9) for (x in two.center.x.toInt()-20 until two.center.x.toInt()+20) {
                    val color = bitmap.getColor(x,y)
                    if (((color ushr 16) and 255)<150 && ((color ushr 8) and 255)<160) ink++
                }
                ink
            } }
            assertTrue(letterInk > 10, "字母可见但不重复加入TalkBack数字标签")
            click(scene, "5");frame(scene, 5);assertEquals(listOf('5'), tones)
            val retiredTone = button(scene,"5").config[SemanticsActions.OnClick].action!!
            click(scene, "关闭键盘");transitionFrames(1);retiredTone()
            assertEquals(listOf('5'), tones, "关闭后首帧的旧动作不能再发数字")
            transitionFrames(49)
            click(scene, "拨号键盘");transitionFrames(5)
            click(scene, "关闭键盘");transitionFrames(3)
            click(scene, "拨号键盘");transitionFrames(50)
            assertTrue(positions.all { it == original }, "完整及反向过程不得重排底部动作")
            val currentCaller = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { s -> s.text == "+1 202 555 0123" } == true }.boundsInRoot
            assertEquals(caller, currentCaller, "身份字号/位置不随开合突变")
            assertTrue(actions.isEmpty());assertEquals(listOf('5'), tones)
            assertEquals(true, visibility.last())
        } finally { scene.close() }
    }

    @Test fun busyAndRecoveryDoNotFlashOrCloseKeypadAndCurrentBindingKeepsPresentation() {
        var state by mutableStateOf(state())
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { CallScreenContent(state, "SIM 1 · 合成线路", CallAudioControls(),
                onAction = {}, onMute = {}, onSpeaker = {}, onDigit = {}, onRestoreAudio = {}, onMinimize = {}, ticking = false) }
        }
        fun keyPixel(): Int {
            val bounds = button(scene, "1").boundsInRoot
            return scene.render(time).use { image -> Bitmap.makeFromImage(image).use { it.getColor(bounds.center.x.toInt(), bounds.top.toInt()+10) } }
        }
        try {
            frame(scene, 50);click(scene,"拨号键盘");frame(scene,50);val color = keyPixel()
            state = state.copy(dtmfBusy = true);frame(scene,20)
            assertTrue(button(scene,"1").config.contains(SemanticsProperties.Disabled))
            assertEquals(color,keyPixel(),"瞬时忙碌只锁输入，不整片闪灰")
            state = state.copy(sync = CallSyncState.Recovering, dtmfSupported = false, dtmfBusy = false);frame(scene,40)
            assertNotNull(button(scene,"关闭键盘"));assertEquals(color,keyPixel())
            state = state.copy(sync = CallSyncState.Ready, dtmfSupported = true,
                binding = CallBinding("synthetic", "call", 2, "epoch"));frame(scene,40)
            assertNotNull(button(scene,"关闭键盘"));assertFalse(button(scene,"1").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }
    private fun frame(scene: ImageComposeScene,count:Int) { repeat(count) { time+=16_000_000L;scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene)=scene.semanticsOwners.flatMap { owner ->
        fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun button(scene:ImageComposeScene,label:String)=nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true && it.config.contains(SemanticsActions.OnClick) }
    private fun click(scene:ImageComposeScene,label:String) { assertTrue(button(scene,label).config[SemanticsActions.OnClick].action!!.invoke()) }
}
