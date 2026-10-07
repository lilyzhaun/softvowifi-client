package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold

/** Two localized, actual Compose previews with synthetic data; not Android acceptance. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationContextPreviewTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private val now = 1790848800000L
    private var time = 0L
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @Test fun renderActualMessageContext() {
        val thread = AppRoute.MessageThread(StoredMessageConversation(owner, peer.drop(1)).handle)
        val state = MessageUiState.Ready(listOf(
            MessageRecord.Incoming("one", peer, "明天下午三点见，可以吗？", now - 180_000),
            MessageRecord.Outgoing("two", peer, "可以，到时联系。", now - 120_000, OutgoingMessageStatus.ACCEPTED),
            MessageRecord.Incoming("three", peer, "好的，明天见。", now - 60_000),
            MessageRecord.Outgoing("selected", peer, "这是一条用于检查长按效果的合成消息。", now, OutgoingMessageStatus.FAILED),
        ), MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner)
        val scene = ImageComposeScene(width = 390, height = 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                    AppSubpageScene(thread, appChromeSpec(AppearanceSettings.Default, false), titleOverride = peer, onBack = {}) {
                        MessageThreadScreen(thread.conversation, state, { _, _ -> error("preview must not send") })
                    }
                } }
            }
        }
        try {
            frame(scene)
            val selected = nodes(scene).last { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
            requireNotNull(selected.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == "消息详情" } == true })
            capture(scene, "context-focus-thread")
        } finally { scene.close() }
    }

    @Test fun renderActualCallHistoryContext() {
        val history = CallHistoryState.Ready(listOf(
            record("one", peer, now, CallOutcome.COMPLETED),
            record("two", "+12025550124", now - 600_000, CallOutcome.MISSED),
            record("three", "+12025550125", now - 1800_000, CallOutcome.COMPLETED),
        ), owner)
        val scene = ImageComposeScene(width = 390, height = 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SoftVoWiFiTheme(false) {
                    AppShell(AppNavigationState.initial(AppRoute.Calls), {}, appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                        if (route == AppRoute.Calls) CallsScreen(
                            CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE), true, {}, history,
                            {}, {}, {}, {}, {}, callbackEnabled = true, onCallback = { error("preview must not call") },
                            lineContent = { CommunicationLineChip("SIM 1 · 测试线路", "通话记录线路", {}) },
                        )
                    }
                }
            }
        }
        try {
            frame(scene)
            val selected = nodes(scene).first { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
            requireNotNull(selected.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { v -> v.text == "复制号码" } == true })
            capture(scene, "context-focus-calls")
        } finally { scene.close() }
    }

    private fun record(id: String, number: String, start: Long, outcome: CallOutcome) = requireNotNull(
        CallHistoryWrite.parse(CallHistoryDraft(id, CallDirection.INCOMING, number,
            if (outcome == CallOutcome.MISSED) CallTimesDraft(start, null, start + 10_000, null)
            else CallTimesDraft(start, start + 1000, start + 61_000, 60), outcome)),
    ).toRecord(owner)
    private fun frame(scene: ImageComposeScene) { repeat(24) { time += 50_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun capture(scene: ImageComposeScene, name: String) {
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { png ->
            Files.write(Path.of("/tmp/opencode/$name.png"), png.bytes)
        } }
    }
}
