package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.*
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

/** Bounded, synthetic production-component films; never Android or communication evidence. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunicationContextMotionCaptureTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private val now = 1790848800000L
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @Test fun captureCallHoldEntranceAndPointerDismissal() = runTest { capture("calls", testScheduler) }
    @Test fun captureMessageHoldEntranceAndPointerDismissal() = runTest { capture("thread", testScheduler) }

    private fun capture(kind: String, scheduler: TestCoroutineScheduler) {
        val thread = AppRoute.MessageThread(StoredMessageConversation(owner, peer.drop(1)).handle)
        val scene = ImageComposeScene(width = 390, height = 844,
            coroutineContext = UnconfinedTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SoftVoWiFiTheme(false) {
                    if (kind == "calls") {
                        AppShell(AppNavigationState.initial(AppRoute.Calls), {}, appearanceSettings = AppearanceSettings.Default,
                            blurSupported = false) { route ->
                            if (route == AppRoute.Calls) CallsScreen(
                                CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE), true, {},
                                CallHistoryState.Ready(listOf(record("one", peer), record("two", "+12025550124")), owner),
                                {}, {}, {}, {}, {}, callbackEnabled = true, onCallback = { error("must not call") },
                                lineContent = { CommunicationLineChip("SIM 1 · 合成线路", "通话记录线路", {}) })
                        }
                    } else Scaffold(Modifier.fillMaxSize()) {
                        AppSubpageScene(thread, appChromeSpec(AppearanceSettings.Default, false), titleOverride = peer, onBack = {}) {
                            MessageThreadScreen(thread.conversation, MessageUiState.Ready(listOf(
                                MessageRecord.Incoming("one", peer, "这是一条用于过程检查的合成消息。", now - 60_000),
                                MessageRecord.Outgoing("selected", peer, "保持原色，动作在消息旁边展开。", now, OutgoingMessageStatus.FAILED),
                            ), MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner), { _, _ -> error("must not send") })
                        }
                    }
                }
            }
        }
        var time = 0L
        var index = 0
        val folder = Path.of("/tmp/opencode/context-motion-$kind-frames")
        Files.createDirectories(folder)
        fun frame(save: Boolean = true) {
            scheduler.advanceTimeBy(16); scheduler.runCurrent(); time += 16_000_000L
            scene.render(time).use { image ->
                if (save) requireNotNull(image.encodeToData()).use { png ->
                    Files.write(folder.resolve("frame-${(index++).toString().padStart(3, '0')}.png"), png.bytes)
                }
            }
        }
        try {
            repeat(30) { frame(false) }; frame()
            val label = if (kind == "calls") "通话记录操作" else "消息操作"
            val source = nodes(scene).filter { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }
                .let { if (kind == "calls") it.first() else it.last() }
            val originalBounds = source.boundsInRoot
            val point = originalBounds.center
            val sampleX = originalBounds.center.x.toInt()
            val sampleY = originalBounds.top.toInt() + 4
            val originalPixel = scene.render(time).use { image -> Bitmap.makeFromImage(image).use { it.getColor(sampleX, sampleY) } }
            scene.sendPointerEvent(PointerEventType.Press, point)
            repeat(30) {
                frame()
                scene.render(time).use { image -> Bitmap.makeFromImage(image).use { pixels ->
                    assertEquals(originalPixel, pixels.getColor(sampleX, sampleY), "识别前没有整块填色")
                } }
            }
            repeat(20) { frame() }
            scene.sendPointerEvent(PointerEventType.Release, point)
            repeat(8) { frame() }
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" })
            val cancel = Offset(300f, 90f)
            scene.sendPointerEvent(PointerEventType.Press, cancel)
            scene.sendPointerEvent(PointerEventType.Release, cancel)
            repeat(20) { frame() }
            val remaining = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview" }
            assertTrue(remaining.isEmpty(), "退场后的焦点层残留：${remaining.map { it.boundsInRoot }}；" +
                "源选中态=${nodes(scene).filter { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }.map { it.config.getOrNull(SemanticsProperties.Selected) }}")
            assertEquals(originalBounds, nodes(scene).filter { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }
                .let { if (kind == "calls") it.first() else it.last() }.boundsInRoot, "退出回到原对象，不移动列表")
        } finally { scene.close() }
    }

    private fun record(id: String, number: String) = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft(id,
        CallDirection.INCOMING, number, CallTimesDraft(now, now + 1000, now + 61_000, 60), CallOutcome.COMPLETED))).toRecord(owner)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
}
