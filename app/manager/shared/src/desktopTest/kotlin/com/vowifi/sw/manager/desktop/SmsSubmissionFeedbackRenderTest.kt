package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageSendAttempt
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsSubmissionFeedbackRenderTest {
    @Test fun lateAcceptedDoesNotDescribeNewBody() = assertLateBody(OutgoingMessageStatus.ACCEPTED)
    @Test fun lateFailedDoesNotDescribeNewBody() = assertLateBody(OutgoingMessageStatus.FAILED)
    @Test fun lateUnknownDoesNotDescribeNewBody() = assertLateBody(OutgoingMessageStatus.UNKNOWN)

    private fun assertLateBody(status: OutgoingMessageStatus) {
        Page().use { page ->
            page.submit()
            page.body.value = "另一份草稿"
            page.complete(status)
            assertNull(page.visibleOutcome())
            assertEquals("另一份草稿", page.body.value)
            assertEquals(0, page.clears)
            assertEquals(1, page.sends.size)
        }
    }

    @Test fun recipientChangedBeforeTerminalCannotReceiveOldFeedback() {
        listOf(OutgoingMessageStatus.ACCEPTED, OutgoingMessageStatus.FAILED, OutgoingMessageStatus.UNKNOWN).forEach { status ->
            Page().use { page ->
                page.submit()
                page.recipient.value = "+12025550124"
                page.complete(status)
                assertNull(page.visibleOutcome())
                assertEquals("提交的草稿", page.body.value)
                assertEquals(0, page.clears)
            }
        }
    }

    @Test fun ownSuccessClearKeepsFeedbackUntilTheNextEditIncludingChangeBack() {
        listOf(OutgoingMessageStatus.ACCEPTED, OutgoingMessageStatus.DELIVERY_REPORTED).forEach { status ->
            listOf(false, true).forEach { clearRecipient -> Page(clearRecipient).use { page ->
                page.submit(); page.complete(status)
                assertEquals(status, page.visibleOutcome())
                assertEquals("", page.body.value)
                assertEquals(1, page.clears)
                if (status == OutgoingMessageStatus.ACCEPTED && clearRecipient) page.capture("matched-success")
                page.edit(1, "新的草稿"); page.edit(1, ""); page.frame()
                assertNull(page.visibleOutcome())
                assertEquals(1, page.sends.size)
            } }
        }
    }

    @Test fun failureAndUnknownStayWithUnchangedDraftButDisappearOnRecipientEdit() {
        listOf(OutgoingMessageStatus.FAILED, OutgoingMessageStatus.UNKNOWN).forEach { status -> Page().use { page ->
            page.submit(); page.complete(status); page.frame()
            assertEquals(status, page.visibleOutcome())
            assertEquals("提交的草稿", page.body.value)
            assertEquals(0, page.clears)
            page.edit(0, "+12025550124"); page.edit(0, "+12025550123"); page.frame()
            assertNull(page.visibleOutcome())
            assertEquals(1, page.sends.size)
        } }
    }

    @Test fun ownerChangeInvalidatesFeedbackAndLateOldOwnerCannotClearNewOwner() {
        Page().use { page ->
            page.submit(); page.complete(OutgoingMessageStatus.UNKNOWN)
            assertEquals(OutgoingMessageStatus.UNKNOWN, page.visibleOutcome())
            page.state.value = page.state.value.copy(ownerId = otherOwner)
            page.frame(); assertNull(page.visibleOutcome())
        }
        Page().use { page ->
            page.submit()
            page.state.value = page.state.value.copy(ownerId = otherOwner)
            page.complete(OutgoingMessageStatus.ACCEPTED)
            assertNull(page.visibleOutcome())
            assertEquals(0, page.clears)
            assertEquals("提交的草稿", page.body.value)
        }
    }

    @Test fun editingAfterFailureAndSubmittingAgainRequiresNewAttempt() {
        Page().use { page ->
            page.submit(); page.complete(OutgoingMessageStatus.FAILED)
            assertEquals(OutgoingMessageStatus.FAILED, page.visibleOutcome())
            page.edit(1, "修订的草稿"); page.frame(); assertNull(page.visibleOutcome())
            page.submit(); assertNull(page.visibleOutcome())
            page.complete(OutgoingMessageStatus.ACCEPTED)
            assertEquals(OutgoingMessageStatus.ACCEPTED, page.visibleOutcome())
            assertEquals(2, page.sends.size)
        }
    }

    private class Page(clearRecipient: Boolean = false) : AutoCloseable {
        val recipient = mutableStateOf("+12025550123")
        val body = mutableStateOf("提交的草稿")
        val state = mutableStateOf(MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner))
        val sends = mutableListOf<Pair<String, String>>()
        var clears = 0
        private var time = 0L
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val scene = ImageComposeScene(width = 420, height = 900) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { SoftVoWiFiTheme(false) {
                AppSubpageScene(AppRoute.NewMessage, appChromeSpec(AppearanceSettings(true, false), false), onBack = {}) {
                    NewMessageScreen(state.value, recipient.value, body.value, { recipient.value = it }, { body.value = it },
                        { clears++; body.value = ""; if (clearRecipient) recipient.value = "" },
                        { number, text -> sends.add(number to text) })
                }
            } }
        }
        init { frame() }
        fun frame() { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
        fun submit() {
            val node = nodes().first { it.config.getOrNull(SemanticsProperties.StateDescription) == "可发送" }
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()); frame()
        }
        fun complete(status: OutgoingMessageStatus) {
            val submitted = sends.last()
            state.value = state.value.copy(sendAttempt = MessageSendAttempt(owner, "attempt-${sends.size}", submitted.first, submitted.second, status))
            frame()
        }
        fun edit(index: Int, value: String) {
            val field = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }[index]
            assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        }
        fun visibleOutcome(): OutgoingMessageStatus? {
            val text = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            return labels.entries.singleOrNull { it.value in text }?.key
        }
        fun capture(name: String) {
            scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
                Files.write(Path.of("/tmp/opencode/issue23-final-$name.png"), data.bytes)
            } }
        }
        private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            descend(owner.rootSemanticsNode)
        }
        override fun close() = scene.close()
    }

    companion object {
        private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        private val otherOwner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
        private val labels = mapOf(
            OutgoingMessageStatus.ACCEPTED to "已受理，不代表接收设备已收到。",
            OutgoingMessageStatus.DELIVERY_REPORTED to "已收到状态报告，不代表本机收到 MT 短信。",
            OutgoingMessageStatus.FAILED to "发送未完成，草稿已保留。",
            OutgoingMessageStatus.UNKNOWN to "发送结果未知，请先核对；不会自动重发。",
        )
    }
}
