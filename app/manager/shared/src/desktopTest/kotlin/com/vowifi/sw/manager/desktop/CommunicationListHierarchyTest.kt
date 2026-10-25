package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunicationListHierarchyTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private val timestamp = 1704067200000L

    @Test fun readAndUnreadConversationsHaveDifferentEmphasisWithoutChangingTheCount() = smsOnEdt {
        val scene = ImageComposeScene(width = 390, height = 400) {
            SoftVoWiFiTheme(false) { Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                ConversationRow(ConversationRowPresentation(peer, "", "已读合成预览", "10:00", null), {}, unreadCount = 0)
                ConversationRow(ConversationRowPresentation("+12025550124", "", "未读合成预览", "11:00", null), {}, unreadCount = 7)
            } }
        }
        try {
            settle(scene)
            assertEquals(FontWeight.Medium, layout(text(scene, peer)).layoutInput.style.fontWeight, "已读身份不能与未读一样粗重")
            assertEquals(FontWeight.SemiBold, layout(text(scene, "+12025550124")).layoutInput.style.fontWeight)
            assertEquals(1, nodes(scene).count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("7 条未读短信") == true })
            assertEquals(2, nodes(scene).count { it.config.contains(SemanticsActions.OnClick) })
        } finally { scene.close() }
    }

    @Test fun longSenderOwnsTheRowWidthBeforeTimestampAndLargeTypeNeverClipsIt() = smsOnEdt {
        val sender = "+12025550123123456789"
        for (fontScale in listOf(1f, 2f)) {
            val scene = ImageComposeScene(width = 360, height = 600, density = Density(1f, fontScale)) {
                SoftVoWiFiTheme(false) { Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    ConversationRow(ConversationRowPresentation(sender, "", "合成预览", "2024年1月1日", "发送结果未知"), {}, unreadCount = 12)
                } }
            }
            try {
                settle(scene)
                val identity = text(scene, sender)
                val timestamp = text(scene, "2024年1月1日")
                assertTrue(timestamp.boundsInRoot.top >= identity.boundsInRoot.bottom,
                    "长身份旁不能再塞日期导致号码碎行：scale=$fontScale，identity=${identity.boundsInRoot}，time=${timestamp.boundsInRoot}")
                assertFalse(layout(identity).hasVisualOverflow)
                assertEquals(17f * fontScale, layout(identity).layoutInput.style.fontSize.value * fontScale)
                assertTrue(nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }.boundsInRoot.height >= 48)
            } finally { scene.close() }
        }
    }

    @Test fun oldConversationShowsADateRatherThanAnAmbiguousTimeOfDay() = smsOnEdt {
        val scene = messages(inbox(listOf(
            summary().copy(timestampMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - 300_000, unreadCount = 7),
            summary().copy(identity = StoredMessageConversation(owner, "12025550124"), peerDisplay = "+12025550124",
                latestMessageId = "synthetic-older", latestPreview = "这是较早日期的已读合成消息。", outgoingStatus = OutgoingMessageStatus.UNKNOWN),
        )))
        try {
            settle(scene)
            assertTrue(has(scene, messageDateSpec(timestamp).label), "旧短信列表必须能区分日期，不能只显示HH:mm")
            assertEquals(1, layout(text(scene, "这是较早日期的已读合成消息。")).lineCount,
                "日期仅占身份行，不能把下方预览也挤出一个孤字行")
            capture(scene, "messages")
        } finally { scene.close() }
    }

    @Test fun noSearchMatchesIsNotAnEmptyMailboxAndHasNoRedundantEndMarker() = smsOnEdt {
        val scene = messages(inbox(emptyList(), query = "没有匹配的合成查询"))
        try {
            settle(scene)
            assertTrue(has(scene, "未找到匹配会话"))
            assertFalse(has(scene, "暂无会话"), "搜索零结果不代表没有历史")
            assertFalse(has(scene, "已显示全部匹配会话"), "空搜索只保留一个状态说明")
        } finally { scene.close() }
    }

    @Test fun storageFailureIsNotAlsoPresentedAsEmptyAndRetryUsesTheExistingCallback() = smsOnEdt {
        var retries = 0
        val scene = messages(MessageUiState.Failure("合成读取失败"), { retries++ })
        try {
            settle(scene)
            assertTrue(has(scene, "消息读取失败"))
            assertFalse(has(scene, "暂无会话"), "读取失败不能声称没有数据")
            val retry = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "重试" } == true && it.config.contains(SemanticsActions.OnClick) }
            requireNotNull(retry.config[SemanticsActions.OnClick].action).invoke()
            assertEquals(1, retries)
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        } finally { scene.close() }
    }

    @Test fun knownEmptyLocalHistoryIsStillReadableWithoutAnEngine() = smsOnEdt {
        var retries = 0
        val scene = messages(MessageUiState.Unavailable("合成离线状态"), { retries++ })
        try {
            settle(scene)
            assertTrue(has(scene, "暂无会话"), "存储已读且零线路时，无引擎不应把本地空历史说成不可读")
            assertFalse(has(scene, "消息不可用"))
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            val retry = nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "刷新短信历史" } == true && it.config.contains(SemanticsActions.OnClick) }
            assertNotNull(retry, "离线空历史不能丢失已有的真实刷新入口")
            requireNotNull(retry.config[SemanticsActions.OnClick].action).invoke()
            assertEquals(1, retries)
        } finally { scene.close() }
    }

    @Test fun inboxLoadingIsRealProgressAndAppendFailurePreservesExistingRows() = smsOnEdt {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        var state by mutableStateOf(inbox(emptyList(), loading = true))
        var retries = 0
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(scheduler)) {
            SoftVoWiFiTheme(false) { AppShell(AppNavigationState.initial(AppRoute.Messages), {},
                appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                if (route == AppRoute.Messages) MessagesScreen(state, {}, {}, onLoadMore = { retries++ })
            } }
        }
        fun loadingFrame() { repeat(24) { scheduler.advanceTimeBy(50); scheduler.runCurrent(); time += 50_000_000L; scene.render(time).close(); scheduler.runCurrent() } }
        try {
            loadingFrame()
            assertTrue(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }, "真读取状态需要进度语义")
            assertFalse(has(scene, "暂无会话"))
            state = inbox(listOf(summary()), failed = true); loadingFrame()
            assertTrue(has(scene, "合成列表预览"))
            assertFalse(has(scene, "暂无会话"))
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            val retry = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "加载失败，重试加载" } == true && it.config.contains(SemanticsActions.OnClick) }
            requireNotNull(retry.config[SemanticsActions.OnClick].action).invoke(); assertEquals(1, retries)
        } finally { scene.close() }
    }

    @Test fun loadedEmptyCallHistoryRemainsReadOnlyWhileTheNextSelectionLoads() = smsOnEdt {
        var history: CallHistoryState by mutableStateOf(CallHistoryState.Ready(emptyList(), owner))
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { AppShell(AppNavigationState.initial(AppRoute.Calls), {},
                appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                if (route == AppRoute.Calls) CallsScreen(CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE),
                    true, {}, history, {}, {}, {}, {}, {})
            } }
        }
        try {
            settle(scene)
            assertTrue(has(scene, "暂无通话记录"), "已读取的空集不能留下无解释空白")
            capture(scene, "calls-empty")
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            history = CallHistoryState.Loading; settle(scene)
            assertFalse(has(scene, "正在读取通话记录"), "B1：有已确认结果的切线不闪加载面板")
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            assertTrue(has(scene, "暂无通话记录"), "只保留既有空态绘制，不能声称新线路已读取完成")
            history = CallHistoryState.Failure("合成存储错误"); settle(scene)
            assertTrue(has(scene, "无法读取通话记录"))
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        } finally { scene.close() }
    }

    private fun summary() = MessageConversationSummary(StoredMessageConversation(owner, peer.drop(1)), peer,
        "合成列表预览", "synthetic", timestamp, null, 1, 0)
    private fun inbox(items: List<MessageConversationSummary>, query: String = "", loading: Boolean = false, failed: Boolean = false) =
        MessageUiState.Inbox(owner, MessageInboxPage(query, items, loading = loading, failed = failed), attachmentNotice = "合成只读说明")
    private fun messages(state: MessageUiState, refresh: () -> Unit = {}) = ImageComposeScene(width = 390, height = 844) {
        SoftVoWiFiTheme(false) { AppShell(AppNavigationState.initial(AppRoute.Messages), {},
            appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
            if (route == AppRoute.Messages) MessagesScreen(state, {}, {},
                historyChoices = MessageHistoryChoices(storageAvailable = true), onRefreshHistory = refresh)
        } }
    }
    private var time = 0L
    private fun settle(scene: ImageComposeScene) { repeat(24) { time += 50_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
    }
    private fun text(scene: ImageComposeScene, text: String) = nodes(scene, unmerged = true).first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && it.config.contains(SemanticsActions.GetTextLayoutResult)
    }
    private fun layout(node: SemanticsNode): TextLayoutResult = mutableListOf<TextLayoutResult>().also {
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(it)
    }.single()
    private fun has(scene: ImageComposeScene, text: String) = nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
    private fun capture(scene: ImageComposeScene, name: String) = scene.render(time).use { image ->
        requireNotNull(image.encodeToData()).use { png ->
            java.nio.file.Files.write(java.nio.file.Path.of("/tmp/opencode/communication-lists-$name.png"), png.bytes)
        }
    }
}
