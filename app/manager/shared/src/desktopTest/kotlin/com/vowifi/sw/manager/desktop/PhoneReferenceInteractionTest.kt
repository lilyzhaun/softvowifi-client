package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import androidx.navigationevent.*
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PhoneReferenceInteractionTest {
    private var time = 0L

    @Test fun acceptedReleaseHasSmallInterruptibleReboundAndFixedHitTarget() = runBlocking {
        var clicks = 0
        val scene = ImageComposeScene(width = 240, height = 200) {
            SoftVoWiFiTheme(false) {
                PhoneButton("动作", { clicks++ }, targetSize = 72.dp,
                    content = { Box(Modifier.size(30.dp).testTag("face")) })
            }
        }
        try {
            frame(scene, 60)
            val target = button(scene, "动作").boundsInRoot
            val base = tagged(scene, "face").boundsInRoot.width
            scene.sendPointerEvent(PointerEventType.Press, target.center); delay(30); frame(scene, 40)
            assertEquals(target.width, button(scene, "动作").boundsInRoot.width, .1f, "触摸区域不随绘制缩放")
            assertEquals(base * .96f, tagged(scene, "face").boundsInRoot.width, .1f)
            scene.sendPointerEvent(PointerEventType.Release, target.center)
            val widths = List(45) { frame(scene, 1); tagged(scene, "face").boundsInRoot.width }
            assertEquals(1, clicks)
            assertTrue(widths.max() > base + .05f, "松手应有可测的微回弹")
            assertTrue(widths.max() < base * 1.015f, "不能夸张放大")
            assertEquals(base, widths.last(), .05f)
            // A new press reverses a release spring rather than queuing a second animation.
            scene.sendPointerEvent(PointerEventType.Press, target.center); delay(30); frame(scene, 8)
            scene.sendPointerEvent(PointerEventType.Release, target.center); frame(scene, 5)
            scene.sendPointerEvent(PointerEventType.Press, target.center); delay(30); frame(scene, 25)
            assertTrue(tagged(scene, "face").boundsInRoot.width < base)
            scene.sendPointerEvent(PointerEventType.Release, target.center); frame(scene, 60)
            assertEquals(3, clicks)
        } finally { scene.close() }
    }

    @Test fun cancelAndDisabledDoNotClickAndZeroMotionHasNoRebound() = runBlocking {
        var factor by mutableStateOf(1f)
        var enabled by mutableStateOf(true)
        var clicks = 0
        val scene = ImageComposeScene(width = 240, height = 200,
            coroutineContext = object : MotionDurationScale { override val scaleFactor get() = factor }) {
            SoftVoWiFiTheme(false) { PhoneButton("动作", { clicks++ }, enabled = enabled,
                content = { Box(Modifier.size(30.dp).testTag("face")) }) }
        }
        try {
            frame(scene, 60)
            val center = button(scene, "动作").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center); delay(30); frame(scene, 30)
            scene.sendPointerEvent(PointerEventType.Move, Offset(230f, 190f)); delay(30)
            scene.sendPointerEvent(PointerEventType.Release, Offset(230f, 190f)); frame(scene, 60)
            assertEquals(0, clicks)
            enabled = false; frame(scene, 10)
            scene.sendPointerEvent(PointerEventType.Press, center); scene.sendPointerEvent(PointerEventType.Release, center); frame(scene, 10)
            assertEquals(0, clicks)
            enabled = true; factor = 0f; frame(scene, 10)
            scene.sendPointerEvent(PointerEventType.Press, center); delay(30); frame(scene, 3)
            scene.sendPointerEvent(PointerEventType.Release, center); frame(scene, 3)
            assertEquals(1, clicks)
            assertEquals(30f, tagged(scene, "face").boundsInRoot.width, .05f)
        } finally { scene.close() }
    }

    @Test fun dialActionIsCompactCenteredAndScrollCancelsDigitPress() = runBlocking {
        var number by mutableStateOf("+12025550123")
        var motion by mutableStateOf(1f)
        val scene = ImageComposeScene(width = 390, height = 844,
            coroutineContext = object : MotionDurationScale { override val scaleFactor get() = motion }) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalWindowInfo provides object : androidx.compose.ui.platform.WindowInfo {
                override val isWindowFocused = true
                override val containerSize = androidx.compose.ui.unit.IntSize(390, 844)
                override val containerDpSize = androidx.compose.ui.unit.DpSize(390.dp, 844.dp)
            }) {
            SoftVoWiFiTheme(false) { PhoneDialerScreen(number, { number = it }, "SIM 1 · Skinny", true, false, null, {}, {}, {}) }
            }
        }
        try {
            frame(scene, 60)
            val dialBounds = button(scene, "拨打").boundsInRoot
            assertEquals(64f, dialBounds.width, .1f, "使用紧凑圆形拨打")
            assertEquals(dialBounds.width, dialBounds.height, .1f)
            assertEquals(195f, dialBounds.center.x, .1f, "拨打居中")
            assertTrue(button(scene, "拨打").boundsInRoot.bottom < 844)
            assertEquals(listOf("拨打"), button(scene, "拨打").config[SemanticsProperties.ContentDescription])
            val field = nodes(scene).single { it.config.contains(SemanticsProperties.InputText) }
            assertEquals("+12025550123", field.config[SemanticsProperties.InputText].text)
            assertEquals("+1 202 555 0123", field.config[SemanticsProperties.EditableText].text)
            val initial = button(scene, "1").boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, initial, type = PointerType.Touch)
            repeat(8) { i ->
                scene.sendPointerEvent(PointerEventType.Move, initial + Offset(0f, (i + 1) * 12f), type = PointerType.Touch)
                delay(16); frame(scene, 1)
            }
            val pulled = button(scene, "1").boundsInRoot.center.y
            assertTrue(pulled > initial.y + 8f, "短页面也应跟手越界位移")
            scene.sendPointerEvent(PointerEventType.Release, initial + Offset(0f, 96f), type = PointerType.Touch)
            frame(scene, 100)
            assertEquals("+12025550123", number, "从数字键开始的滑动不能输入数字")
            assertEquals(initial.y, button(scene, "1").boundsInRoot.center.y, 1f)
            motion = 0f; frame(scene, 5)
            scene.sendPointerEvent(PointerEventType.Press, initial, type = PointerType.Touch)
            repeat(8) { i ->
                scene.sendPointerEvent(PointerEventType.Move, initial + Offset(0f, (i + 1) * 12f), type = PointerType.Touch)
                delay(16); frame(scene, 1)
            }
            assertEquals(initial.y, button(scene, "1").boundsInRoot.center.y, 1f, "零动画不产生越界位移")
            scene.sendPointerEvent(PointerEventType.Release, initial + Offset(0f, 96f), type = PointerType.Touch)
            frame(scene, 5); assertEquals("+12025550123", number)
        } finally { scene.close() }
    }

    @Test fun historySearchFiltersCurrentOwnerAndOwnerChangeClearsQuery() {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        fun record(phone: String) = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft(phone,
            CallDirection.OUTGOING, phone, CallTimesDraft(1000, 2000, 62000, 60), CallOutcome.COMPLETED))).toRecord(owner)
        var history by mutableStateOf<CallHistoryState>(CallHistoryState.Ready(listOf(record("155501"), record("155502")), owner))
        var navigated = 0
        val input = object : NavigationEventInput() { fun back() = dispatchOnBackCompleted() }
        val navOwner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher(onBackCompletedFallback = { navigated++ }).apply { addInput(input) }
        }
        val scene = ImageComposeScene(width = 390, height = 844) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navOwner) {
            SoftVoWiFiTheme(false) { CallsScreen(CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE),
                true, {}, history, {}, {}, {}, {}, {}) }
            }
        }
        try {
            frame(scene, 60)
            click(scene, "搜索通话记录"); frame(scene, 30)
            val field = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("501")); frame(scene, 30)
            assertTrue(texts(scene).contains("155501")); assertFalse(texts(scene).contains("155502"))
            field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("999")); frame(scene, 30)
            assertTrue(texts(scene).contains("没有匹配的通话记录"))
            input.back(); frame(scene, 30)
            assertEquals(0, navigated); assertTrue(texts(scene).contains("155502"))
            assertFalse(nodes(scene).any { it.config.contains(SemanticsActions.SetText) })
            click(scene, "搜索通话记录"); frame(scene, 30)
            nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action!!
                .invoke(androidx.compose.ui.text.AnnotatedString("999")); frame(scene, 30)
            history = CallHistoryState.Ready(listOf(record("155502")), requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43))))
            frame(scene, 30)
            assertTrue(texts(scene).contains("155502")); assertFalse(texts(scene).contains("没有匹配的通话记录"))
        } finally { scene.close(); navOwner.navigationEventDispatcher.dispose() }
    }

    @Test fun systemBackClosesDtmfBeforeMinimizingAndNeverHangsUp() {
        var minimized = 0
        val actions = mutableListOf<CallAction>()
        val digits = mutableListOf<Char>()
        val input = object : NavigationEventInput() { fun back() = dispatchOnBackCompleted() }
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher(onBackCompletedFallback = { minimized++ }).apply { addInput(input) }
        }
        val scene = ImageComposeScene(width = 390, height = 844) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                SoftVoWiFiTheme(false) { CallScreenContent(CallUiState.Ready(CallPhase.InCall, CallAudioState.Active, CallOrigin.DESKTOP_FIXTURE,
                    binding = CallBinding("synthetic", "call", 1, "epoch"), dtmfSupported = true, peer = CallPeer.parse("+12025550123")),
                    "SIM 1 · 合成线路", CallAudioControls(), onAction = actions::add, onMute = {}, onSpeaker = {},
                    onDigit = digits::add, onRestoreAudio = {}, onMinimize = { minimized++ }, ticking = false) }
            }
        }
        try {
            frame(scene, 60); click(scene, "拨号键盘"); frame(scene, 60)
            click(scene, "5"); frame(scene, 5)
            assertEquals(listOf('5'), digits)
            input.back(); frame(scene, 60)
            assertEquals(0, minimized)
            assertTrue(actions.isEmpty())
            assertFalse(texts(scene).contains("关闭键盘"))
            click(scene, "拨号键盘"); frame(scene, 60)
            assertTrue(texts(scene).contains("通话按键"))
            input.back(); frame(scene, 60); input.back(); frame(scene, 10)
            assertEquals(1, minimized); assertTrue(actions.isEmpty())
        } finally { scene.close(); owner.navigationEventDispatcher.dispose() }
    }

    @Test fun captureThreeProductionScreensWithSyntheticData() {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val history = CallHistoryState.Ready(listOf("12025550123", "12025550148", "12025550167", "12025550186", "12025550194").mapIndexed { index, phone ->
            val start = now - (index + 1) * 3_600_000L
            requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("preview-$index",
                if (index % 2 == 0) CallDirection.OUTGOING else CallDirection.INCOMING, phone,
                CallTimesDraft(start, if (index == 1) null else start + 1000, start + 205000, if (index == 1) null else 204),
                if (index == 1) CallOutcome.MISSED else CallOutcome.COMPLETED))).toRecord(owner)
        }, owner)
        val lifecycle = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        }
        fun capture(name: String, content: @Composable () -> Unit) {
            val scene = ImageComposeScene(width = 390, height = 844) {
                CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycle) {
                    SoftVoWiFiTheme(false, content = content)
                }
            }
            try {
                frame(scene, 90)
                val directory = java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/phone-reference"))
                scene.render(time).use { image -> image.encodeToData()!!.use {
                    java.nio.file.Files.write(directory.resolve("$name.png"), it.bytes)
                } }
            } finally { scene.close() }
        }
        capture("history") {
            AppShell(com.vowifi.sw.manager.ui.navigation.AppNavigationState.initial(com.vowifi.sw.manager.ui.navigation.AppRoute.Calls), {},
                appearanceSettings = AppearanceSettings.Default.copy(floatingNavigation = false), blurSupported = false) {
                CallsScreen(CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE), true, {}, history, {}, {}, {}, {}, {},
                    lineContent = { CommunicationLineChip("SIM 1 · Skinny", "选择通话线路", {}) }, hasRegisteredEngine = true)
            }
        }
        capture("dialer") { PhoneDialerScreen("+12025550123", {}, "SIM 1 · Skinny", true, false, null, {}, {}, {}) }
        capture("incall") { CallScreenContent(CallUiState.Ready(CallPhase.InCall, CallAudioState.Active, CallOrigin.DESKTOP_FIXTURE,
            binding = CallBinding("synthetic", "call", 1, "epoch"), dtmfSupported = true, peer = CallPeer.parse("+12025550123"),
            clock = CallSessionClock(204000) { 0 }), "SIM 1 · Skinny", CallAudioControls(speakerOn = true),
            onAction = {}, onMute = {}, onSpeaker = {}, onDigit = {}, onRestoreAudio = {}, onMinimize = {}, ticking = false) }
    }

    private fun frame(scene: ImageComposeScene, count: Int) { repeat(count) { time += 16_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.unmergedRootSemanticsNode)
    }
    private fun button(scene: ImageComposeScene, label: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick)
    }
    private fun tagged(scene: ImageComposeScene, tag: String) = nodes(scene).first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun click(scene: ImageComposeScene, label: String) { assertTrue(button(scene, label).config[SemanticsActions.OnClick].action!!.invoke()) }
}
