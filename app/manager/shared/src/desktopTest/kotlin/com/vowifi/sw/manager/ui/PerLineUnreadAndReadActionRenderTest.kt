@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

class PerLineUnreadAndReadActionRenderTest {
    @Test fun pendingReadAndUnreadDescribeTheirActualActionWithoutChangingCountsBeforeCommit() = onMotionTestThread { runTest {
        var release = CompletableDeferred<Unit>()
        withInbox(beforeAction = { release.await() }) { d ->
            assertTrue(d.row().config[SemanticsActions.CustomActions].single { it.label == "标为已读" }.action())
            d.frames()
            assertEquals("正在标为已读", d.pendingDescription())
            assertEquals(5L, (d.source.unreadSummary.value as MessageUnreadSummary.Ready).total)
            release.complete(Unit); d.frames()
            assertEquals(3L, (d.source.unreadSummary.value as MessageUnreadSummary.Ready).total)
            release = CompletableDeferred()
            assertTrue(d.row().config[SemanticsActions.CustomActions].single { it.label == "标为未读" }.action())
            d.frames()
            assertEquals("正在标为未读", d.pendingDescription())
            assertEquals(3L, (d.source.unreadSummary.value as MessageUnreadSummary.Ready).total)
            release.complete(Unit); d.frames()
            assertEquals(4L, (d.source.unreadSummary.value as MessageUnreadSummary.Ready).total)
        }
    } }

    @Test fun currentLineAndPickerExposeTheirOwnUnreadCountsInsteadOfOnlyTheGlobalTotal() = onMotionTestThread { runTest {
        withInbox { d ->
            assertEquals(5L, assertIs<MessageUnreadSummary.Ready>(d.source.unreadSummary.value).total)
            assertEquals(OWNER_A, d.source.historyChoices.value.selectedOwner)
            val chip = d.lineChip()
            assertTrue(d.text(chip).contains("2 条未读"), "当前A线路须显示自己的2条未读，不能只有底栏总5")
            chip.config[SemanticsActions.OnClick].action!!.invoke(); d.frames()
            val radio = s4Nodes(d.e, false).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            assertEquals(2, radio.size, "前提：真实持久化A/B两线路都在面板")
            val selected = radio.single { it.config.getOrNull(SemanticsProperties.Selected) == true }
            val other = radio.single { it.config.getOrNull(SemanticsProperties.Selected) != true }
            assertTrue(d.text(selected).contains("2 条未读"),
                "已选面板行须保A计数，实际=${d.text(selected)}，A=${(d.source.unreadSummary.value as? MessageUnreadSummary.Ready)?.byOwner?.get(OWNER_A)}")
            assertTrue(d.text(other).contains("3 条未读"), "B同号会话不能并入A或被A当前页覆盖")
            assertEquals(5L, d.repository.countUnreadIncoming(), "仅打开选线面板不标读")
            repeat(98) { index -> d.repository.insertIncomingIfAbsent(OWNER_B, "extra-$index", "engine-extra-$index", "+155501",
                "synthetic extra unread", 10L + index, 10) }
            d.source.refresh(); d.frames()
            val updated = s4Nodes(d.e, false).single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                it.config.getOrNull(SemanticsProperties.Selected) != true }
            assertTrue(d.text(updated).contains("99+ 条未读"))
            assertEquals("101 条未读", updated.config[SemanticsProperties.StateDescription], "可见数封顶，但语义保实际数")
            assertEquals(103L, assertIs<MessageUnreadSummary.Ready>(d.source.unreadSummary.value).total)
        }
    } }

    @Test fun menuAndAccessibleReadActionPersistOnlyTheTargetOwnerAndReconcileItsNotification() = onMotionTestThread { runTest {
        withInbox { d ->
            val row = d.row()
            val accessible = row.config[SemanticsActions.CustomActions]
            assertEquals(2L, (d.source.state.value as MessageUiState.Inbox).page.items.single().unreadCount)
            row.config[SemanticsActions.OnLongClick].action!!.invoke(); d.frames()
            assertTrue(s4HasText(d.e, "标为已读"), "未读会话须提供真实标为已读，而不是仍只有标为未读")
            assertFalse(s4HasText(d.e, "标为未读"))
            assertTrue(accessible.any { it.label == "标为已读" })
            s4Action(d.e, "标为已读").config[SemanticsActions.OnClick].action!!.invoke(); d.frames()
            assertEquals(3L, assertIs<MessageUnreadSummary.Ready>(d.source.unreadSummary.value).total)
            assertTrue(d.repository.listByOwner(OWNER_A).filterIsInstance<MessageRecord.Incoming>().all { it.isRead })
            assertEquals(3, d.repository.listByOwner(OWNER_B).filterIsInstance<MessageRecord.Incoming>().count { !it.isRead })
            assertEquals(StoredMessageConversation(OWNER_A, "155501") to emptySet<String>(), d.reconciled.last())
            assertTrue(d.row().config[SemanticsActions.CustomActions].any { it.label == "标为未读" })
            assertFalse(d.text(d.lineChip()).contains("条未读"), "A归零后不显示计数，不能把B的3条当A未读")
            d.row().config[SemanticsActions.CustomActions].single { it.label == "标为未读" }.action(); d.frames()
            assertEquals(4L, assertIs<MessageUnreadSummary.Ready>(d.source.unreadSummary.value).total)
            assertTrue(d.text(d.lineChip()).contains("1 条未读"), "现有人工未读锚点也属于该卡计数")
            d.row().config[SemanticsActions.CustomActions].single { it.label == "标为已读" }.action(); d.frames()
            assertEquals(3L, assertIs<MessageUnreadSummary.Ready>(d.source.unreadSummary.value).total)
            assertEquals(StoredMessageConversation(OWNER_A, "155501") to emptySet<String>(), d.reconciled.last())
        }
    } }

    private suspend fun TestScope.withInbox(beforeAction: suspend () -> Unit = {}, block: suspend (Driver) -> Unit) {
        val repository = FakeMessageRepository()
        for ((owner, count) in listOf(OWNER_A to 2, OWNER_B to 3)) repeat(count) { index ->
            val suffix = if (owner == OWNER_A) "a" else "b"
            repository.insertIncomingIfAbsent(owner, "$suffix-$index", "engine-$suffix-$index", "+155501", "synthetic unread", index.toLong(), 1)
        }
        repository.setLastOwner(OWNER_A)
        val reconciled = mutableListOf<Pair<StoredMessageConversation, Set<String>>>()
        val observer = object : MessagePersistenceObserver {
            override fun onSnapshot(owner: SimOwnerId, records: List<MessageRecord>, freshIncomingId: String?) = Unit
            override fun onThreadUnread(conversation: StoredMessageConversation, ids: Set<String>) { reconciled += conversation to ids }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = EngineMessageSource(EngineMessageRuntime({ emptyList() }, { error("只读历史不得连接引擎") }, { 1_700_000_000_000L }),
            backgroundScope.coroutineContext + dispatcher, repository, { null }, { error("本用例不得发送") }, observer)
        try { Driver(this, repository, source, reconciled, beforeAction).use { d -> d.frames(); block(d) } }
        finally { source.close(); runCurrent(); repository.close() }
    }

    private class Driver(scope: TestScope, val repository: FakeMessageRepository, val source: EngineMessageSource,
        val reconciled: List<Pair<StoredMessageConversation, Set<String>>>, beforeAction: suspend () -> Unit) : AutoCloseable {
        private val messages = object : MessageSource by source {
            override suspend fun changeConversation(handle: String, action: MessageConversationAction): MessageConversationActionResult {
                beforeAction()
                return source.changeConversation(handle, action)
            }
        }
        private val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
            FixtureOperationalLogSource("empty"), messages, FixtureCallSource("unavailable"))
        private val appearance = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false))
        val e = MotionTestEnvironment(scope.testScheduler, 420, 900, dispatcher = StandardTestDispatcher(scope.testScheduler)) {
            s4SmsHost { App() }
        }
        @Composable private fun App() = SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES,
            appearanceSource = appearance, forceDark = false)
        fun frames() { repeat(60) { e.advanceFrameBy(16) } }
        fun lineChip() = s4Nodes(e, false).single { it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.startsWith("短信线路") } }
        fun row() = s4Nodes(e, false).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作" }
        fun pendingDescription() = s4Nodes(e, false).mapNotNull { it.config.getOrNull(SemanticsProperties.StateDescription) }
            .single { it.startsWith("正在标为") }
        fun text(node: SemanticsNode): String = (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.children.map(::text)).joinToString(" ")
        override fun close() { e.close() }
    }
}
