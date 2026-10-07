package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import java.nio.file.Files
import java.nio.file.Path
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.Test

/** Production overlay hosts/controls; all data synthetic. No device/carrier evidence. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationCompleteCaptureTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val second = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private var time = 0L
    @Test fun completeCommunicationOverlaysRenderAsOneVocabulary() {
        val choices = MessageHistoryChoices(listOf(MessageHistoryLine(owner, 4, true, simLabel = "SIM 1 · Skinny"),
            MessageHistoryLine(second, 2, false, simLabel = "SIM 2 · Lebara")), owner, true)
        capture("line-picker", false) { MessageLinePicker(choices, {}, {}, {}, null) }
        capture("remark", false) { MessageRemarkDialog(MessageRemarkEditor(second, "旅行备用", 2, "SIM 2 · Lebara"), {}) { _, _ -> MessageRemarkSaveResult.Failed } }
        capture("clear-history", false) { ClearCallHistoryConfirmation({}, {}) }
        capture("replace-draft", true) { MessageThreadActions(MessageRecord.Outgoing("failed", "+447700900123", "合成失败短信", 1,
            OutgoingMessageStatus.FAILED), "保留的草稿", true, {}, {}, {}, SmsMessageAction.REEDIT) }
        capture("message-details", false) { MessageThreadActions(MessageRecord.Outgoing("unknown", "+447700900123", "合成消息", 1,
            OutgoingMessageStatus.UNKNOWN), "", true, {}, {}, {}, SmsMessageAction.DETAILS) }
        capture("message-menu", false) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 160.dp)) {
                CompositionLocalProvider(LocalSmsBubbleMenu provides SmsBubbleMenu("selected", setOf("selected"), true, {}, {}, {})) {
                    OutgoingMessageBubble(MessageThreadItem.OutgoingBubble("selected", "合成失败消息，长按后出现就地菜单。",
                        MessageTimestampSpec(1), "发送失败", null))
                }
            }
        }
        capture("call-ended", true) { CallEndContent("通话已结束，未触发真实呼叫。", {}) }
    }
    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(dark) {
                Scaffold(Modifier.fillMaxSize()) { content() }
            } }
        }
        try {
            repeat(14) { time += 100_000_000L; scene.render(time).close() }
            scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
                val directory = Files.createDirectories(Path.of("build/reports/communication-complete"))
                Files.write(directory.resolve("$name.png"), data.bytes)
            } }
        } finally { scene.close() }
    }
}
