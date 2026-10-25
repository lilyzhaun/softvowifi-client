@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text

class CommunicationRootCompletionRenderTest {
    @Test fun confirmedNoRegisteredLineProvidesOneRealHomeEntryOnBothCommunicationRoots() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) for (root in listOf(AppDestination.CALLS, AppDestination.MESSAGES))
            Driver(testScheduler, root, scale = scale).use { d ->
            d.frames(40)
            val label = if (root == AppDestination.CALLS) "暂无已注册线路，暂时不能拨号" else "暂无已注册线路，暂时不能发送短信"
            assertFalse(s4HasText(d.e, label), "名册未知不能冒无注册")
            d.lines.value = d.lines.value.copy(rosterComplete = true); d.frames(40)
            assertTrue(s4HasText(d.e, label), "完整名册确认无注册后须提供紧凑业务说明")
            assertEquals(1, s4Actions(d.e, "前往首页").size)
            d.calls.value = (d.calls.value as CallUiState.Ready).copy(phase = CallPhase.InCall); d.frames(40)
            assertFalse(s4HasText(d.e, label), "进行中不重复显示不能拨号/发送提示")
            d.calls.value = d.idle; d.frames(40)
            d.lines.value = d.lines.value.copy(choices = d.lines.value.choices.map { it.copy(registered = true) }); d.frames(40)
            assertFalse(s4HasText(d.e, label), "注册后须退出")
            d.lines.value = d.lines.value.copy(choices = d.lines.value.choices.map { it.copy(registered = false) }); d.frames(40)
            if (root == AppDestination.CALLS) d.calls.value = d.idle.copy(operationMessage = "合成已有不可用说明")
            else d.messages.value = d.empty.copy(attachmentNotice = "合成已有不可用说明")
            d.frames(40)
            assertFalse(s4HasText(d.e, label), "已有说明不重复")
            d.calls.value = d.idle; d.messages.value = d.empty; d.frames(40)
            s4Tap(d.e, s4Action(d.e, "前往首页").boundsInRoot.center); d.frames(60)
            val selected = s4Nodes(d.e, false).single { it.config.getOrNull(SemanticsProperties.Selected) == true }
            assertTrue(s4Matches(selected, "首页"), "必须走真实App根导航，不是可点no-op")
        }
    } }

    @Test fun confirmedMessagesRemainDrawnButTheirOldActionsAreRevokedDuringTheNextRead() = onMotionTestThread { runTest {
        val records = listOf(MessageRecord.Incoming("retained", "+155501", "合成旧确认消息", 1, isRead = true))
        Driver(testScheduler, AppDestination.MESSAGES,
            MessageUiState.Ready(records, MessageOrigin.ENGINE, ownerId = OWNER_A)).use { d ->
            d.frames(40)
            val row = s4Nodes(d.e, false).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作" }
            val open = row.config[SemanticsActions.OnClick].action!!
            val custom = row.config[SemanticsActions.CustomActions]
            assertTrue(s4HasText(d.e, "合成旧确认消息"))
            d.messages.value = MessageUiState.Loading
            repeat(4) { d.e.advanceFrameBy(0) }
            open(); custom.forEach { assertFalse(it.action()) }
            d.frames(30)
            assertTrue(s4HasText(d.e, "合成旧确认消息"), "等待下一次读取时保原绘制")
            assertFalse(s4HasText(d.e, "正在读取消息"), "已有内容不能重播首读进度")
            assertEquals(0, d.opens, "旧页面回调不能在读取期间打开会话")
            d.messages.value = MessageUiState.Ready(listOf(MessageRecord.Incoming("new", "+155502", "合成新确认消息", 2)),
                MessageOrigin.ENGINE, ownerId = OWNER_B)
            d.frames(40)
            assertTrue(s4HasText(d.e, "合成新确认消息")); assertFalse(s4HasText(d.e, "合成旧确认消息"))
        }
    } }

    @Test fun sourceAndInboxFirstLoadingDoNotFlashBefore150ms() = onMotionTestThread { runTest {
        for (inbox in listOf(false, true)) Driver(testScheduler, AppDestination.MESSAGES,
            if (inbox) MessageUiState.Inbox(OWNER_A, MessageInboxPage()) else MessageUiState.Loading).use { d ->
            d.e.advanceFrameBy(1); d.advance(100)
            assertFalse(s4HasText(d.e, "正在读取消息"), "L1：源与空Inbox首读150ms前都不能闪进度")
            d.messages.value = d.empty; repeat(4) { d.e.advanceFrameBy(0) }; d.frames(40)
            assertFalse(s4HasText(d.e, "正在读取消息")); assertTrue(s4HasText(d.e, "暂无会话"))
        }
    } }

    @Test fun slowFirstLoadingKeepsIts300msMinimumIncludingZeroMotion() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) Driver(testScheduler, AppDestination.MESSAGES, MessageUiState.Loading, scale).use { d ->
            d.e.advanceFrameBy(1); d.advance(151)
            assertTrue(s4HasText(d.e, "正在读取消息"))
            d.messages.value = d.empty; repeat(4) { d.e.advanceFrameBy(0) }; d.advance(298)
            assertTrue(s4HasText(d.e, "正在读取消息"), "显示不足300ms不能提前揭露结果")
            d.advance(500)
            assertFalse(s4HasText(d.e, "正在读取消息")); assertTrue(s4HasText(d.e, "暂无会话"))
        }
    } }

    @Test fun failedInboxFirstReadOffersRetryThatRefreshesHistoryRatherThanPagingMore() = onMotionTestThread { runTest {
        Driver(testScheduler, AppDestination.MESSAGES,
            MessageUiState.Inbox(OWNER_A, MessageInboxPage(loading = false, failed = true))).use { d ->
            d.frames(40); assertTrue(s4HasText(d.e, "消息读取失败"))
            assertTrue(s4HasText(d.e, "重试"), "首屏失败按钮统一为重试")
            val before = d.refreshes
            s4Tap(d.e, s4Action(d.e, "重试").boundsInRoot.center); d.frames(40)
            assertEquals(before + 1, d.refreshes)
            assertEquals(0, d.loads, "重试首读不能误用加载下一页")
        }
    } }

    @Test fun threadSearchUses200msEntranceAnd150msExitInsteadOfTheOld120msPair() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
            var open by mutableStateOf(false)
            MotionTestEnvironment(testScheduler, 420, 900, scale, dispatcher = StandardTestDispatcher(testScheduler)) { s4SmsHost {
                Column { MessageThreadSearch(open, "", 0, { open = !open }, {})
                    Text("合成搜索尾锚点", Modifier.testTag("search-tail")) }
            } }.use { e ->
                fun frames(n: Int) { repeat(n) { e.advanceFrameBy(16) } }
                fun y() = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.TestTag) == "search-tail" }.boundsInRoot.top
                frames(30); val closed = y()
                open = true; repeat(4) { e.advanceFrameBy(0) }; frames(8); val entering = y()
                frames(30); val full = y()
                assertTrue(full > closed + 40f, "前提：实际搜索展开改变正文几何")
                if (scale > 0f) assertTrue(entering < full - .5f, "128ms尚在200ms展开中，不得120ms已完成：$entering/$full")
                else assertEquals(full, entering, .5f)
                open = false; repeat(4) { e.advanceFrameBy(0) }; frames(8); val exiting = y()
                if (scale > 0f) assertTrue(exiting > closed + .5f, "128ms尚在150ms退出中，不得120ms已卸载")
                frames(30); assertEquals(closed, y(), .5f)
            }
        }
    } }

    private class Driver(scheduler: TestCoroutineScheduler, root: AppDestination,
        initial: MessageUiState? = null, scale: Float = 1f) : AutoCloseable {
        val empty = MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = OWNER_A)
        val idle = CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE)
        val messages = MutableStateFlow(initial ?: empty)
        val calls = MutableStateFlow<CallUiState>(idle)
        val lines = MutableStateFlow(CallLineState(listOf(CallLineChoice(OWNER_A, "合成线路", true, true, false, true, true, running = true)),
            selectedOwner = OWNER_A))
        var refreshes = 0; var loads = 0; var opens = 0
        private val messageSource = object : MessageSource {
            override val state = messages
            override fun refresh() { refreshes++ }
            override fun loadMoreHistory() { loads++ }
            override fun selectConversation(handle: String?) { if (handle != null) opens++ }
        }
        private val callSource = object : CallSource by FixtureCallSource("idle") {
            override val state = calls
            override val lines = this@Driver.lines
            override fun refresh() = Unit
        }
        private val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
            FixtureOperationalLogSource("empty"), messageSource, callSource)
        private val appearance = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false))
        val e = MotionTestEnvironment(scheduler, 420, 900, scale, dispatcher = StandardTestDispatcher(scheduler)) { s4SmsHost {
            SoftVoWiFiApp(sources, {}, false, "test", initialDestination = root, appearanceSource = appearance, forceDark = false)
        } }
        fun frames(n: Int) { repeat(n) { e.advanceFrameBy(16) } }
        fun advance(ms: Long) { repeat((ms / 16).toInt()) { e.advanceFrameBy(16) }; e.advanceFrameBy(ms % 16) }
        override fun close() { e.close() }
    }
}
