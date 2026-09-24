package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PhoneInteractionRenderTest {
    private var time = 0L
    private val binding = CallBinding("synthetic-owner", "synthetic-call", 1, "synthetic-epoch")
    private fun ready() = CallUiState.Ready(CallPhase.InCall, CallAudioState.Active, CallOrigin.DESKTOP_FIXTURE,
        peer = CallPeer.parse("+12025550123"), binding = binding, dtmfSupported = true)

    @Test fun rejectCanAbortMicrophonePreparation() = rejectsPendingAnswer(preparing = true)

    @Test fun rejectCanSupersedePendingAnswer() = rejectsPendingAnswer(preparing = false)

    private fun rejectsPendingAnswer(preparing: Boolean) = runBlocking {
        val actions = mutableListOf<CallAction>()
        var state by mutableStateOf(ready().copy(phase = CallPhase.Ringing, dtmfSupported = false,
            pendingAction = if (preparing) null else CallAction.Answer))
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(true) {
                CallScreenContent(state, "合成测试线路", CallAudioControls(), preparing = preparing,
                    onAction = { actions.add(it); state = state.copy(pendingAction = it) },
                    onMute = {}, onSpeaker = {}, onDigit = {}, onRestoreAudio = {}, onMinimize = {}, ticking = false)
            }
        }
        try {
            frame(scene)
            val reject = button(scene, "拒接")
            assertFalse(reject.config.contains(SemanticsProperties.Disabled))
            assertTrue(reject.boundsInRoot.width >= 48 && reject.boundsInRoot.height >= 48)
            assertTrue(button(scene, "接听").config.contains(SemanticsProperties.Disabled))
            tap(scene, "接听"); frame(scene)
            assertTrue(actions.isEmpty())
            tap(scene, "拒接"); frame(scene)
            assertEquals(listOf(CallAction.Reject), actions)
            assertTrue(button(scene, "拒接").config.contains(SemanticsProperties.Disabled))
            tap(scene, "拒接"); tap(scene, "接听"); frame(scene)
            assertEquals(listOf(CallAction.Reject), actions)
        } finally { scene.close() }
    }

    @Test fun pointerDialRunsOnceAndBusyFreezesKeysAndEditor() = runBlocking {
        var busy by mutableStateOf(false)
        var number by mutableStateOf("+12025550123")
        var calls = 0
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) {
                PhoneDialerScreen(number, { number = it }, "合成线路", true, busy, null,
                    { calls++; busy = true }, {}, {})
            }
        }
        try {
            frame(scene)
            assertFalse(button(scene, "拨打").config.contains(SemanticsProperties.Selected))
            tap(scene, "拨打"); frame(scene)
            assertEquals(1, calls)
            assertTrue(button(scene, "正在准备").config.contains(SemanticsProperties.Disabled))
            tap(scene, "正在准备"); tap(scene, "1"); tap(scene, "删除号码"); frame(scene)
            assertEquals(1, calls)
            assertEquals("+12025550123", number)
            val field = nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("电话号码") == true }
            assertTrue(field.config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }

    @Test fun largeCjkKeypadScrollsToHangupAndClearsDigitsOnClose() {
        val digits = mutableListOf<Char>()
        val actions = mutableListOf<CallAction>()
        val scene = ImageComposeScene(width = 320, height = 740) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SoftVoWiFiTheme(true) {
                    CallScreenContent(ready(), "当前绑定的合成测试线路", CallAudioControls(),
                        onAction = actions::add, onMute = {}, onSpeaker = {}, onDigit = digits::add,
                        onRestoreAudio = {}, onMinimize = {}, ticking = false)
                }
            }
        }
        try {
            frame(scene); click(scene, "拨号键盘"); frame(scene)
            click(scene, "5"); frame(scene)
            assertEquals(listOf('5'), digits)
            assertTrue(actions.isEmpty())
            val scroll = nodes(scene).single { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 4000f); frame(scene)
            val hangup = button(scene, "挂断")
            assertTrue(hangup.boundsInRoot.top >= 0 && hangup.boundsInRoot.bottom <= 740)
            click(scene, "关闭键盘"); frame(scene)
            click(scene, "拨号键盘"); frame(scene)
            assertTrue(nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { it.text == "通话按键" })
            assertEquals(listOf('5'), digits)
        } finally { scene.close() }
    }

    @Test fun pressSpringInterpolatesAndZeroDurationSettlesWithoutDuplicateClicks() = runBlocking {
        var factor by mutableStateOf(1f)
        val motion = object : MotionDurationScale { override val scaleFactor get() = factor }
        var calls = 0
        val scene = ImageComposeScene(width = 240, height = 160, coroutineContext = motion) {
            SoftVoWiFiTheme(false) { PhoneButton("测试动作", { calls++ }, wide = true) }
        }
        try {
            frame(scene)
            val original = button(scene, "测试动作").boundsInRoot
            screenshot(scene, "motion-rest")
            scene.sendPointerEvent(PointerEventType.Press, original.center); delay(30)
            frame(scene, 3, 16_000_000L)
            val early = button(scene, "测试动作").boundsInRoot.width
            screenshot(scene, "motion-mid")
            frame(scene)
            val pressed = button(scene, "测试动作").boundsInRoot.width
            screenshot(scene, "motion-pressed")
            assertTrue(early > pressed && early < original.width)
            assertEquals(original.width * .96f, pressed, .2f)
            scene.sendPointerEvent(PointerEventType.Release, original.center); frame(scene)
            assertEquals(1, calls)
            assertEquals(original.width, button(scene, "测试动作").boundsInRoot.width, .2f)
            factor = 0f; frame(scene)
            scene.sendPointerEvent(PointerEventType.Press, original.center); delay(30); frame(scene, 2, 16_000_000L)
            assertEquals(original.width * .96f, button(scene, "测试动作").boundsInRoot.width, .2f)
            scene.sendPointerEvent(PointerEventType.Release, original.center); frame(scene)
            assertEquals(2, calls)
        } finally { scene.close() }
    }

    @Test fun duplicateFailureHasOneExplanationAndOnlyRealRecoveryCallbacks() {
        var restored = 0
        var permissions = 0
        val failure = "音频暂不可用，请恢复音频"
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(true) {
                CallScreenContent(ready().copy(audio = CallAudioState.Failed(failure), operationMessage = failure),
                    "合成测试线路", CallAudioControls(message = failure), onAction = {}, onMute = {}, onSpeaker = {},
                    onDigit = {}, onRestoreAudio = { restored++ }, onMinimize = {}, ticking = false,
                    onPermissions = { permissions++ })
            }
        }
        try {
            frame(scene)
            assertEquals(1, nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.count { it.text == failure })
            assertTrue(button(scene, "静音").config.contains(SemanticsProperties.Disabled))
            click(scene, "恢复音频"); click(scene, "应用权限")
            assertEquals(1, restored); assertEquals(1, permissions)
            screenshot(scene, "audio-error-dark")
        } finally { scene.close() }
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
            Files.write(Path.of("/tmp/opencode/phone-polish-$name.png"), data.bytes)
        } }
    }

    @Test fun phoneActionsRespectHapticsPreferenceAndLongPressIsExclusive() = runBlocking {
        val haptics = mutableListOf<HapticFeedbackType>()
        var enabled by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var preference by mutableStateOf(true)
        var clicks = 0
        var longs = 0
        val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        val scene = ImageComposeScene(width = 240, height = 160) {
            CompositionLocalProvider(LocalHapticFeedback provides recorder) {
                PhoneActionFeedback(AppearanceSettings.Default.copy(hapticsEnabled = preference)) {
                SoftVoWiFiTheme(false) {
                    PhoneButton("测试动作", { clicks++ }, enabled = enabled, busy = busy, wide = true, onLongClick = { longs++ })
                }
                }
            }
        }
        try {
            frame(scene); tap(scene, "测试动作"); frame(scene)
            assertEquals(1, clicks); assertEquals(1, haptics.size, "accepted tap must use app haptics")
            val center = button(scene, "测试动作").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center); delay(700); frame(scene)
            scene.sendPointerEvent(PointerEventType.Release, center); frame(scene)
            assertEquals(1, clicks); assertEquals(1, longs); assertEquals(2, haptics.size)
            preference = false; frame(scene); tap(scene, "测试动作"); frame(scene)
            scene.sendPointerEvent(PointerEventType.Press, center); delay(700); frame(scene)
            scene.sendPointerEvent(PointerEventType.Release, center); frame(scene)
            assertEquals(2, clicks); assertEquals(2, longs); assertEquals(2, haptics.size)
            enabled = false; frame(scene); tap(scene, "测试动作"); frame(scene)
            enabled = true; busy = true; frame(scene); tap(scene, "测试动作"); frame(scene)
            assertEquals(2, clicks); assertEquals(2, longs); assertEquals(2, haptics.size)
        } finally { scene.close() }
    }

    @Test fun pressedSurfaceChangesBeforeSpringSettles() = runBlocking {
        val scene = ImageComposeScene(width = 240, height = 160) {
            SoftVoWiFiTheme(false) { PhoneButton("测试动作", {}, wide = true) }
        }
        fun pixel(): Int = scene.render(time).use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { it.getColor(24, 24) } }
        try {
            frame(scene); val rest = pixel()
            scene.sendPointerEvent(PointerEventType.Press, button(scene, "测试动作").boundsInRoot.center)
            delay(30); frame(scene, 2, 8_000_000L)
            val pressed = pixel()
            println("press surface rest=$rest pressed=$pressed after=16ms")
            val directory = Files.createDirectories(Path.of("build/reports/dialer"))
            scene.render(time).use { image -> requireNotNull(image.encodeToData()).use {
                Files.write(directory.resolve("press.png"), it.bytes)
            } }
            assertNotEquals(rest, pressed, "pressed surface must change without waiting for scale animation")
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene, count: Int = 12, step: Long = 100_000_000L) {
        repeat(count) { time += step; scene.render(time).close() }
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
    private fun button(scene: ImageComposeScene, label: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick)
    }
    private fun click(scene: ImageComposeScene, label: String) {
        assertTrue(requireNotNull(button(scene, label).config[SemanticsActions.OnClick].action).invoke())
    }
    private suspend fun tap(scene: ImageComposeScene, label: String) {
        val center = button(scene, label).boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, center); delay(30)
        scene.sendPointerEvent(PointerEventType.Release, center)
    }
}
