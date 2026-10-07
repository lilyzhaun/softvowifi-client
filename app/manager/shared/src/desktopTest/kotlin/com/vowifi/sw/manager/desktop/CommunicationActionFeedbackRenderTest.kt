package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationActionFeedbackRenderTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private val other = "+12025550124"
    private var time = 0L
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @Test fun conversationPressSoftensContentWithoutPaintingOrShrinkingItsTextOrHitArea() = runBlocking {
        var clicks = 0
        val scene = ImageComposeScene(width = 390, height = 240) {
            SoftVoWiFiTheme(false) {
                ConversationRow(ConversationRowPresentation(peer, "", "合成预览", "10:00", null), { clicks++ })
            }
        }
        try {
            frame(scene)
            val action = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }
            val before = action.boundsInRoot
            val numberBefore = text(scene, peer).boundsInRoot
            val restPixel = scene.render(time).use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { it.getColor(200, 4) } }
            val restAvatar = scene.render(time).use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { it.getColor(20, 32) } }
            scene.sendPointerEvent(PointerEventType.Press, before.center)
            delay(150); frame(scene)
            assertEquals(before.width, nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }.boundsInRoot.width, .2f,
                "开放行不能跟小按钮一起缩放")
            assertEquals(numberBefore.width, text(scene, peer).boundsInRoot.width, .2f)
            val pressedPixel = scene.render(time).use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { it.getColor(200, 4) } }
            assertEquals(restPixel, pressedPixel, "用户拒绝整行按压色块，背景必须保持不变")
            val pressedAvatar = scene.render(time).use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { it.getColor(20, 32) } }
            assertNotEquals(restAvatar, pressedAvatar, "内容仍有轻微回应，不能只移除全部反馈")
            scene.sendPointerEvent(PointerEventType.Release, before.center); frame(scene)
            assertEquals(1, clicks)
        } finally { scene.close() }
    }

    @Test fun messageLongPressMarksTheActualBubbleAndRespectsTheHapticsPreference() {
        for (enabled in listOf(true, false)) {
            val haptics = mutableListOf<HapticFeedbackType>()
            val recorder = object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
            }
            val scene = ImageComposeScene(width = 390, height = 844) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalHapticFeedback provides recorder) {
                    SoftVoWiFiTheme(false) { PhoneActionFeedback(AppearanceSettings.Default.copy(hapticsEnabled = enabled)) {
                        Scaffold(Modifier.fillMaxSize()) {
                            MessageThreadScreen(StoredMessageConversation(owner, peer.drop(1)).handle,
                                MessageUiState.Ready(listOf(MessageRecord.Incoming("message", peer, "合成正文", 1)),
                                    MessageOrigin.ENGINE, ownerId = owner), { _, _ -> error("must not send") })
                        }
                    } }
                }
            }
            try {
                frame(scene)
                val bubble = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
                assertTrue(requireNotNull(bubble.config[SemanticsActions.OnLongClick].action).invoke()); frame(scene)
                assertTrue(nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
                    .config.getOrNull(SemanticsProperties.Selected) == true, "操作对象必须有明确选中状态")
                assertEquals(if (enabled) 1 else 0, haptics.size)
                assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == "消息详情" } == true })
            } finally { scene.close() }
        }
    }

    @Test fun copyingACallNumberDoesNotMoveTheNextHistoryRow() = runBlocking {
        val clipboard = RecordingClipboard()
        val scene = callsScene(clipboard)
        try {
            frame(scene)
            val nextNumber = displayCallNumber(other)
            assertTrue(nextNumber.startsWith("+"), "国际历史展示必须保留原前缀")
            val nextTop = text(scene, nextNumber).boundsInRoot.top
            copyNumber(scene)
            assertNotNull(clipboard.entry)
            assertEquals(nextTop, text(scene, nextNumber).boundsInRoot.top, .2f, "复制反馈不能增加历史行高度")
            assertTrue(hasText(scene, "已复制号码"))
        } finally { scene.close() }
    }

    @Test fun callCopyFeedbackExpiresRatherThanStayingInHistoryForever() = runBlocking {
        val scene = callsScene(RecordingClipboard())
        try {
            frame(scene); copyNumber(scene)
            assertTrue(hasText(scene, "已复制号码"))
            delay(3200); frame(scene)
            assertFalse(hasText(scene, "已复制号码"), "短暂结果必须自动消失")
        } finally { scene.close() }
    }

    @Test fun callHistoryTapUsesTheSameAppHapticsGateAsConversations() {
        for (enabled in listOf(true, false)) {
            val haptics = mutableListOf<HapticFeedbackType>()
            var opened = 0
            val recorder = object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
            }
            val scene = callsScene(RecordingClipboard(), enabled, recorder) { opened++ }
            try {
                frame(scene)
                val row = nodes(scene).first { it.config.contains(SemanticsActions.OnLongClick) }
                requireNotNull(row.config[SemanticsActions.OnClick].action).invoke()
                assertEquals(1, opened)
                assertEquals(if (enabled) 1 else 0, haptics.size)
            } finally { scene.close() }
        }
    }

    @Test fun repeatedCopyResetsTheFeedbackLifetime() = runBlocking {
        val scene = callsScene(RecordingClipboard())
        try {
            frame(scene); copyNumber(scene)
            delay(1300); copyNumber(scene)
            delay(1000); frame(scene)
            assertTrue(hasText(scene, "已复制号码"), "重复成功复制要重新计时")
            delay(1400); frame(scene)
            assertFalse(hasText(scene, "已复制号码"))
        } finally { scene.close() }
    }

    @Test fun leavingTheRouteClearsTheResultRatherThanShowingItOnReentry() {
        val active = mutableStateOf(true)
        val scene = callsScene(RecordingClipboard(), active = { active.value })
        try {
            frame(scene); copyNumber(scene)
            assertTrue(hasText(scene, "已复制号码"))
            active.value = false; frame(scene)
            assertFalse(hasText(scene, "已复制号码"))
            active.value = true; frame(scene)
            assertFalse(hasText(scene, "已复制号码"))
        } finally { scene.close() }
    }

    @Test fun leavingTheThreadClosesTheBubbleMenuAndDoesNotRestoreSelection() {
        val active = mutableStateOf(true)
        val scene = ImageComposeScene(width = 390, height = 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalAppRouteActive provides active.value) {
                SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                    MessageThreadScreen(StoredMessageConversation(owner, peer.drop(1)).handle,
                        MessageUiState.Ready(listOf(MessageRecord.Incoming("message", peer, "合成正文", 1)),
                            MessageOrigin.ENGINE, ownerId = owner), { _, _ -> error("must not send") })
                } }
            }
        }
        try {
            frame(scene)
            val bubble = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
            requireNotNull(bubble.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
            assertTrue(hasText(scene, "消息详情"))
            active.value = false; frame(scene)
            assertFalse(hasText(scene, "消息详情"), "失活线程不能留有可操作的消息菜单")
            active.value = true; frame(scene)
            assertFalse(hasText(scene, "消息详情"))
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Selected) == true })
        } finally { scene.close() }
    }

    private fun callsScene(clipboard: Clipboard, hapticsEnabled: Boolean = true,
        haptics: HapticFeedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
        }, active: () -> Boolean = { true }, onOpen: () -> Unit = {}) = ImageComposeScene(width = 390, height = 844) {
        CompositionLocalProvider(LocalClipboard provides clipboard, LocalHapticFeedback provides haptics,
            LocalAppRouteActive provides active()) {
            SoftVoWiFiTheme(false) { PhoneActionFeedback(AppearanceSettings.Default.copy(hapticsEnabled = hapticsEnabled)) {
                Scaffold(Modifier.fillMaxSize()) {
                    CallsScreen(CallUiState.Unavailable("合成历史"), true, {},
                        CallHistoryState.Ready(listOf(record("one", peer), record("two", other)), owner),
                        {}, {}, {}, {}, {}, onOpenHistory = { onOpen() })
                }
            } }
        }
    }
    private fun record(id: String, number: String) = requireNotNull(CallHistoryWrite.parse(
        CallHistoryDraft(id, CallDirection.OUTGOING, number, CallTimesDraft(if (id == "one") 1000 else 500, 2000, 62000, 60), CallOutcome.COMPLETED),
    )).toRecord(owner)
    private fun copyNumber(scene: ImageComposeScene) {
        val row = nodes(scene).first { it.config.contains(SemanticsActions.OnLongClick) }
        requireNotNull(row.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
        val copy = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == "复制号码" } == true &&
            it.config.contains(SemanticsActions.OnClick) }
        requireNotNull(copy.config[SemanticsActions.OnClick].action).invoke(); frame(scene)
    }
    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 50_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun text(scene: ImageComposeScene, value: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true
    }
    private fun hasText(scene: ImageComposeScene, value: String) = nodes(scene).any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true
    }
    private class RecordingClipboard : Clipboard {
        override val nativeClipboard: Any = Any()
        var entry: ClipEntry? = null
        override suspend fun getClipEntry() = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    }
}
