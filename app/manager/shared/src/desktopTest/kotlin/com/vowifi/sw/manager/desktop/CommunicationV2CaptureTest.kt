package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Synthetic inputs, actual production Compose components; never device or carrier evidence. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationV2CaptureTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+447700900123"
    private val line = "SIM 2 · Lebara"
    private val choices = MessageHistoryChoices(listOf(MessageHistoryLine(owner, 4, true, simLabel = line)), owner, true)
    private val callLines = CallLineState(listOf(CallLineChoice(owner, line, true, true, true, true, true)), true, owner)
    private val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    private val messages = MessageUiState.Ready(listOf(
        MessageRecord.Incoming("one", peer, "明天下午三点见，可以吗？", now - 180_000),
        MessageRecord.Outgoing("two", peer, "好的，明天见。", now - 120_000, OutgoingMessageStatus.ACCEPTED),
        MessageRecord.Incoming("three", peer, "好的，到时联系。", now - 60_000),
        MessageRecord.Incoming("four", "Service", "合成通知，字母发件人不等于可回复号码。", now),
    ), MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner)
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private var time = 0L

    @Test fun productionRootsAndInnerPagesRenderAtPhoneAndLargeCjkSizes() {
        captureRoot(AppRoute.Messages, "messages-light", false)
        captureRoot(AppRoute.Calls, "calls-light", false)
        captureRoot(AppRoute.Messages, "messages-dark-large", true, 320, 2f)
        capture("dialer-light", false) { PhoneDialerScreen(peer, {}, line, true, false, null, {}, {}, {},
            lineContent = { CallLineEntry(callLines, line, {}) }) }
        capture("incall-dark", true) { CallScreenContent(CallUiState.Ready(CallPhase.InCall, CallAudioState.Active,
            CallOrigin.ENGINE, peer = CallPeer.parse(peer), binding = CallBinding("synthetic-owner", "synthetic-call", 1),
            dtmfSupported = true), line, CallAudioControls(), onAction = {}, onMute = {}, onSpeaker = {},
            onDigit = {}, onRestoreAudio = {}, onMinimize = {}, ticking = false) }
        val thread = AppRoute.MessageThread(StoredMessageConversation(owner, peer.drop(1)).handle)
        capture("thread-light", false) {
            AppSubpageScene(thread, appChromeSpec(AppearanceSettings.Default, false), titleOverride = peer, onBack = {}) {
                MessageThreadScreen(thread.conversation, messages, { _, _ -> error("capture must never send") }, historyChoices = choices)
            }
        }
        capture("new-dark-large", true, 320, 2f) {
            AppSubpageScene(AppRoute.NewMessage, appChromeSpec(AppearanceSettings.Default, false), onBack = {}) {
                NewMessageScreen(messages, peer, "保留的合成草稿", {}, {}, {}, { _, _ -> error("capture must never send") }, historyChoices = choices)
            }
        }
    }

    private fun captureRoot(root: AppRoute.Root, name: String, dark: Boolean, width: Int = 390, scale: Float = 1f) {
        capture(name, dark, width, scale) {
            AppShell(AppNavigationState.initial(root), {}, appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                when (route) {
                    AppRoute.Messages -> MessagesScreen(messages, {}, {}, historyChoices = choices)
                    AppRoute.Calls -> CallsScreen(CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE), true, {},
                        CallHistoryState.Ready(listOf(CallRecord(CallDirection.INCOMING, CallPeerPhone.parse(peer),
                            now - 180_000, null, now - 120_000, null, CallOutcome.MISSED))), {}, {}, {}, {}, {},
                        lineContent = { CallLineEntry(callLines, line, {}) })
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun capture(name: String, dark: Boolean, width: Int = 390, scale: Float = 1f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = 844, density = Density(1f, scale)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(dark, content) }
        }
        try {
            repeat(12) { time += 100_000_000L; scene.render(time).close() }
            assertTrue(scene.semanticsOwners.isNotEmpty())
            scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
                val directory = Files.createDirectories(Path.of("build/reports/communication-v2"))
                Files.write(directory.resolve("$name.png"), data.bytes)
            } }
        } finally { scene.close() }
    }
}
