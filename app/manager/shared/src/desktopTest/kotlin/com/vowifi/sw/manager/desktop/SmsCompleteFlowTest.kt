package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsCompleteFlowTest {
    @Test fun threadFailureAndUnknownStayBesideTheirDraftWithoutResending() = smsOnEdt {
        for (status in listOf(OutgoingMessageStatus.FAILED, OutgoingMessageStatus.UNKNOWN)) Page(thread = true).use { page ->
            page.submitTwice(); page.complete(status)
            val result = page.text(labels.getValue(status))
            val input = page.fields().single()
            assertTrue(result.boundsInRoot.bottom <= input.boundsInRoot.top, "线程提交结果要在输入区旁可见")
            assertEquals(draft, page.bodyValue())
            assertEquals(1, page.sends.size)
            page.edit(page.fields().single(), "新合成草稿")
            assertFalse(page.has(labels.getValue(status)), "过期结果不能继续描述新草稿")
            assertEquals(1, page.sends.size)
        }
    }

    @Test fun threadSuccessClearsTheMatchedDraftAndRetainsTheHonestReceipt() = smsOnEdt {
        for (status in listOf(OutgoingMessageStatus.ACCEPTED, OutgoingMessageStatus.DELIVERY_REPORTED)) Page(thread = true).use { page ->
            page.submitTwice(); page.complete(status)
            assertEquals("", page.bodyValue())
            assertTrue(page.has(labels.getValue(status)), "清稿之后仍须说明受理/报告的真实含义")
            assertFalse(page.has("已送达"))
            page.edit(page.fields().single(), "后续合成草稿")
            assertFalse(page.has(labels.getValue(status)))
            assertEquals(1, page.sends.size)
        }
    }

    @Test fun newMessageFeedbackRemainsAboveTheComposerWhenTheContentIsTallerThanTheViewport() = smsOnEdt {
        Page(height = 500, notice = "这是一段仅用于主机布局回归的合成线路恢复提示。".repeat(8)).use { page ->
            page.submitTwice(); page.complete(OutgoingMessageStatus.FAILED)
            val result = page.text(labels.getValue(OutgoingMessageStatus.FAILED)).boundsInRoot
            val input = page.fields().last().boundsInRoot
            assertTrue(result.top >= 0 && result.bottom <= input.top, "缩高窗口里结果仍需在composer上方可见")
            assertTrue(input.top - result.bottom <= 20, "结果不能留在可滚动的号码/线路列表里")
            assertTrue(input.bottom <= 500)
            assertEquals(draft, page.bodyValue())
            page.capture("new-result")
        }
    }

    @Test fun permissionRejectionReleasesPendingAndRecoveryRequiresANewExplicitSubmission() = smsOnEdt {
        Page().use { page ->
            page.submitTwice()
            page.state = MessageUiState.PermissionDenied("合成授权拒绝")
            page.frame()
            page.state = ready()
            page.frame()
            assertTrue(page.hasState("可发送"), "权限恢复不能永久停在本地发送中")
            assertEquals(draft, page.bodyValue())
            assertEquals(1, page.sends.size, "恢复不能自动重发")
            page.complete(OutgoingMessageStatus.ACCEPTED)
            assertEquals(draft, page.bodyValue(), "已结束等待的旧结果不能清空恢复后的草稿")
            assertFalse(page.has(labels.getValue(OutgoingMessageStatus.ACCEPTED)))
            page.submitTwice(); page.complete(OutgoingMessageStatus.ACCEPTED)
            assertEquals(2, page.sends.size)
            assertEquals("", page.bodyValue())
        }
    }

    @Test fun threadInitialLoadingHasProgressAndFailureRetriesThroughTheExistingCallback() = smsOnEdt {
        Page(thread = true, initial = MessageUiState.Loading).use { page ->
            assertTrue(page.has("正在读取消息"))
            assertTrue(page.nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }, "线程初读不能伪装成静态空页")
            page.state = MessageUiState.Failure("合成历史读取失败"); page.frame()
            assertTrue(page.has("消息读取失败"))
            assertFalse(page.nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            page.click("重试")
            assertEquals(1, page.retries)
            page.state = ready(); page.frame()
            assertTrue(page.has(historyBody))
            assertEquals(draft, page.bodyValue())
        }
    }

    @Test fun threadNoMatchExplainsItsScopeAndClosingRestoresTheTimelineAndDraft() = smsOnEdt {
        Page(thread = true).use { page ->
            page.click("搜索会话")
            page.edit(page.fields().first(), "NoMatchSyntheticQuery")
            assertTrue(page.has("未找到匹配消息"), "线程搜索零结果要说明发生了什么")
            assertTrue(page.has("仅搜索已加载消息 · 0 条"))
            val resultTop = page.text("未找到匹配消息").boundsInRoot.top
            val queryBottom = page.text("仅搜索已加载消息 · 0 条").boundsInRoot.bottom
            assertTrue(resultTop - queryBottom in 0f..100f, "零匹配说明必须靠近搜索条件，而非沉到composer旁")
            assertFalse(page.has(historyBody))
            assertEquals(draft, page.bodyValue())
            page.capture("thread-search-empty")
            page.click("关闭会话搜索")
            assertTrue(page.has(historyBody))
            assertFalse(page.has("未找到匹配消息"))
            assertEquals(draft, page.bodyValue())
            assertEquals(1, page.fields().size)
            assertTrue(page.sends.isEmpty())
        }
    }

    private class Page(private val thread: Boolean = false, height: Int = 740, notice: String? = null,
        initial: MessageUiState = ready()) : AutoCloseable {
        var state by mutableStateOf(initial)
        private var recipient by mutableStateOf(peer)
        private var body by mutableStateOf(draft)
        private val drafts = mutableStateOf(mapOf(handle.value to draft))
        val sends = mutableListOf<Pair<String, String>>()
        var retries = 0
        private var time = 0L
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val scene = ImageComposeScene(width = 390, height = height) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalThreadDrafts provides drafts) {
                SoftVoWiFiTheme(false) {
                    val route = if (thread) AppRoute.MessageThread(handle) else AppRoute.NewMessage
                    AppSubpageScene(route, appChromeSpec(AppearanceSettings.Default, false), onBack = {}) {
                        if (thread) MessageThreadScreen(handle, state, { number, text -> sends.add(number to text) },
                            onLoadOlder = { retries++ })
                        else NewMessageScreen(state, recipient, body, { recipient = it }, { body = it },
                            { recipient = ""; body = "" }, { number, text -> sends.add(number to text) }, selectionNotice = notice)
                    }
                }
            }
        }
        init { frame() }
        fun frame() { repeat(24) { time += 40_000_000L; scene.render(time).close() } }
        fun bodyValue() = if (thread) drafts.value[handle.value].orEmpty() else body
        fun submitTwice() {
            val node = nodes().single { it.config.getOrNull(SemanticsProperties.StateDescription) == "可发送" }
            val action = requireNotNull(node.config[SemanticsActions.OnClick].action)
            action(); action(); frame()
        }
        fun complete(status: OutgoingMessageStatus) {
            val submitted = sends.last()
            state = (state as MessageUiState.Ready).copy(sendAttempt = MessageSendAttempt(owner,
                "synthetic-attempt-${sends.size}", submitted.first, submitted.second, status))
            frame()
        }
        fun click(label: String) {
            val node = nodes().single { (it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()); frame()
        }
        fun edit(node: SemanticsNode, value: String) {
            assertTrue(requireNotNull(node.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value))); frame()
        }
        fun fields() = nodes().filter { it.config.contains(SemanticsActions.SetText) }
        fun has(label: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        fun hasState(label: String) = nodes().any { it.config.getOrNull(SemanticsProperties.StateDescription) == label }
        fun text(label: String): SemanticsNode = assertNotNull(nodes(unmerged = true).firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }, "缺少应当可见的公共状态：$label")
        fun nodes(unmerged: Boolean = false): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            tree(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
        }
        fun capture(name: String) = scene.render(time).use { image ->
            requireNotNull(image.encodeToData()).use { data ->
                java.nio.file.Files.write(java.nio.file.Path.of("/tmp/opencode/sms-flow-$name.png"), data.bytes)
            }
        }
        override fun close() = scene.close()
    }

    companion object {
        private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        private const val peer = "+12025550123"
        private const val draft = "合成提交草稿"
        private const val historyBody = "本地合成历史消息"
        private val handle = StoredMessageConversation(owner, peer.drop(1)).handle
        private fun ready() = MessageUiState.Ready(listOf(MessageRecord.Incoming("synthetic-incoming", peer,
            historyBody, 1704067200000L)), MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner)
        private val labels = mapOf(
            OutgoingMessageStatus.ACCEPTED to "已受理，不代表接收设备已收到。",
            OutgoingMessageStatus.DELIVERY_REPORTED to "已收到状态报告，不代表本机收到 MT 短信。",
            OutgoingMessageStatus.FAILED to "发送未完成，草稿已保留。",
            OutgoingMessageStatus.UNKNOWN to "发送结果未知，请先核对；不会自动重发。",
        )
    }
}
