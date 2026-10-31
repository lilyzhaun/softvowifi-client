@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

class CallHistoryActionsAndRetryRenderTest {
    @Test fun realDetailOpensTheOwnersPersistedConversationOrPrefilledComposerAndAnimatesBackToDetail() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) for (exists in listOf(true, false)) {
            val f = CallAggregateFixture(coroutineContext)
            val stored = FakeMessageRepository()
            val peer = "+12025550123"
            if (exists) stored.insertIncomingIfAbsent(OWNER_A, "target-a", "engine-a", peer, "synthetic target history", T0, T0)
            stored.insertIncomingIfAbsent(OWNER_B, "target-b", "engine-b", peer, "synthetic other owner", T0, T0)
            repeat(55) { index -> stored.insertIncomingIfAbsent(OWNER_A, "new-$index", "engine-$index", "1556${index.toString().padStart(4, '0')}",
                "synthetic newer thread", T0 + index + 1, T0) }
            val transport = FakeEngineMessageTransport()
            val messages = EngineMessagePersistenceFixtures.sourceOf(emptyList(), transport, stored)
            try {
                runCurrent()
                f.repository.upsert(OWNER_A, historyWrite(peer = peer, endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
                f.source.selectLine(OWNER_A); messages.selectHistoryOwner(OWNER_A); runCurrent()
                val handle = callHistoryGroups(assertIs<CallHistoryState.Ready>(f.source.history.value)).single().handle
                val inbox = assertIs<MessageUiState.Inbox>(messages.state.value)
                assertTrue(inbox.page.items.none { it.identity == StoredMessageConversation(OWNER_A, PeerNumberNormalizer.normalize(peer)) },
                    "前提：目标不在当前已加载首页")
                MotionTestEnvironment(testScheduler, 420, 900, scale) { s4SmsHost {
                    App(f.source, AppRoute.CallHistoryDetail(handle), messages = messages)
                } }.use { e ->
                    s4Frames(e, 60)
                    val selectedCallOwner = f.source.lines.value.selectedOwner
                    s4Tap(e, s4Action(e, "短信").boundsInRoot.center)
                    // 160ms is inside miuix's 500ms push. At 64ms the incoming editor can still
                    // be wholly clipped outside the viewport, whose semantics bounds are Rect.Zero.
                    s4Frames(e, 10)
                    fun editor() = s4Nodes(e).first { it.config.contains(SemanticsActions.SetText) }
                    assertTrue(editor().boundsInRoot.width > 1, "中帧采样前提：真实输入框已经进入视口")
                    val middle = editor().boundsInRoot.left
                    s4Frames(e, 40)
                    val settled = editor().boundsInRoot.left
                    if (scale > 0f) assertTrue(middle > settled + 1 && middle < 420,
                        "真实Nav3推入须有中帧，不瞬切/先闪根：$middle/$settled exists=$exists")
                    else assertEquals(settled, middle, .5f, "零动画直接到位")
                    if (exists) {
                        assertFalse(s4HasText(e, "新建短信"))
                        assertTrue(s4HasText(e, "synthetic target history"))
                        assertFalse(s4HasText(e, "synthetic other owner"))
                    } else {
                        assertTrue(s4HasText(e, "新建短信"))
                        assertEquals(peer, editor().config[SemanticsProperties.EditableText].text)
                        assertEquals("", s4Nodes(e).last { it.config.contains(SemanticsActions.SetText) }
                            .config[SemanticsProperties.EditableText].text)
                    }
                    assertEquals(selectedCallOwner, f.source.lines.value.selectedOwner, "不切换通话线路")
                    s4Tap(e, s4Action(e, "返回").boundsInRoot.center); s4Frames(e, 45)
                    assertTrue(s4HasText(e, "1 次通话 · 当前线路本机记录"), "返回保留原详情，不跳根/短信列表")
                    assertTrue(s4HasText(e, "短信"))
                    assertTrue(transport.requests.none { it.method == "sendSms" })
                }
            } finally { messages.close(); f.source.close(); runCurrent(); stored.close() }
        }
    } }

    @Test fun pendingSmsLookupIsSingleFlightCancelledOnBackAndCannotStealNewNavigation() = onMotionTestThread { runTest {
        val f = CallAggregateFixture(coroutineContext)
        val stored = FakeMessageRepository()
        val backend = EngineMessagePersistenceFixtures.sourceOf(emptyList(), FakeEngineMessageTransport(), stored)
        val gate = CompletableDeferred<Unit>()
        var queries = 0; var cancelled = false
        val messages = object : MessageSource by backend {
            override suspend fun lookupConversation(owner: SimOwnerId, recipient: String): MessageConversationLookup {
                queries++
                try { gate.await(); return MessageConversationLookup.Missing }
                finally { cancelled = true }
            }
        }
        try {
            runCurrent()
            f.repository.upsert(OWNER_A, historyWrite(peer = "+12025550123", endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
            f.source.selectLine(OWNER_A); runCurrent()
            val handle = callHistoryGroups(assertIs<CallHistoryState.Ready>(f.source.history.value)).single().handle
            MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                App(f.source, AppRoute.CallHistoryDetail(handle), messages = messages)
            } }.use { e ->
                s4Frames(e, 60)
                val old = s4Action(e, "短信").config[SemanticsActions.OnClick].action!!
                old(); old(); s4Frames(e, 2)
                assertEquals(1, queries)
                assertTrue(s4HasText(e, "正在打开短信"))
                assertFalse(cancelled)
                s4Tap(e, s4Action(e, "返回").boundsInRoot.center); s4Frames(e, 40)
                assertTrue(cancelled, "离开详情必须取消等待，不只忽略返回结果")
                gate.complete(Unit); old(); s4Frames(e, 40)
                assertEquals(1, queries)
                assertFalse(s4HasText(e, "新建短信"))
                assertFalse(s4HasText(e, "1 次通话 · 当前线路本机记录"))
                assertTrue(s4HasText(e, "通话"))
            }
        } finally { backend.close(); f.source.close(); runCurrent(); stored.close() }
    } }

    @Test fun realAppDetailSharesListAuthorizationAndUnreadableSmsLookupStaysOnTheDetail() = onMotionTestThread { runTest {
        val f = CallAggregateFixture(coroutineContext)
        runCurrent()
        try {
            f.repository.upsert(OWNER_A, historyWrite(peer = "+12025550123", endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
            f.source.selectLine(OWNER_A)
            runCurrent()
            val handle = callHistoryGroups(assertIs<CallHistoryState.Ready>(f.source.history.value)).single().handle
            val dials = mutableListOf<Pair<CallBinding, String>>()
            var hasDial by mutableStateOf(true)
            MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                App(f.source, AppRoute.CallHistoryDetail(handle), if (hasDial) { binding, number -> dials += binding to number } else null)
            } }.use { e ->
                s4Frames(e, 60)
                val call = s4Action(e, "回拨")
                assertFalse(call.config.contains(SemanticsProperties.Disabled))
                val old = call.config[SemanticsActions.OnClick].action!!
                val binding = assertNotNull(f.source.lines.value.outgoingBinding)
                s4Tap(e, call.boundsInRoot.center); s4Frames(e, 2)
                assertEquals(listOf(binding to "+12025550123"), dials, "详情走真实App的同一onCallDial入口")
                s4Tap(e, s4Action(e, "短信").boundsInRoot.center); s4Frames(e, 30)
                assertTrue(s4HasText(e, "无法打开短信，请稍后重试。"))
                assertTrue(s4HasText(e, "1 次通话 · 当前线路本机记录"), "读取失败不能冒无会话而打开新建")

                f.source.selectLine(OWNER_B)
                f.source.selectHistoryOwner(f.source.historyOwners.value.single { it.owner == OWNER_A })
                old(); assertEquals(1, dials.size, "旧回调不得向新选线路拨号")
                s4Frames(e, 30)
                assertTrue(actionIncludingDisabled(e, "回拨").config.contains(SemanticsProperties.Disabled))
                assertTrue(s4HasText(e, "请先选择此记录所属线路后回拨。"))
                assertFalse(s4Action(e, "短信").config.contains(SemanticsProperties.Disabled))

                f.source.selectLine(OWNER_A); f.instances = emptyList(); f.source.refresh(); s4Frames(e, 30)
                assertTrue(actionIncludingDisabled(e, "回拨").config.contains(SemanticsProperties.Disabled))
                assertTrue(s4HasText(e, "当前线路不可拨号，请检查线路注册状态。"))
                hasDial = false; s4Frames(e, 4)
                assertTrue(s4HasText(e, "当前入口不支持拨号。"))
                assertEquals(1, dials.size)
                assertTrue(f.first.requests.isEmpty() && f.second.requests.isEmpty(), "受控宿主回调不是实机通信")
            }
        } finally { f.source.close(); advanceUntilIdle() }
    } }

    @Test fun unknownPeerDoesNotOfferCallbackOrSms() = onMotionTestThread { runTest {
        val history = CallHistoryState.Ready(listOf(historyWrite(peer = null, direction = CallDirection.INCOMING,
            endedAtMs = T0 + 1000, outcome = CallOutcome.MISSED).toRecord(OWNER_A)), OWNER_A)
        val handle = callHistoryGroups(history).single().handle
        MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
            CallHistoryDetailScreen(handle, history, { _, _ -> })
        } }.use { e ->
            s4Frames(e, 30)
            assertTrue(s4HasText(e, "未知号码"))
            assertFalse(s4HasText(e, "回拨")); assertFalse(s4HasText(e, "短信"))
        }
    } }

    @Test fun actualRetryButtonRecoversTheSelectedRepositoryWithoutAnEngineRefresh() = onMotionTestThread { runTest {
        val f = CallAggregateFixture(coroutineContext)
        runCurrent()
        try {
            f.repository.upsert(OWNER_A, historyWrite(endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
            f.source.selectLine(OWNER_A)
            runCurrent()
            MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost { App(f.source) } }.use { e ->
                s4Frames(e, 60)
                f.repository.failNextList = true
                f.source.selectHistoryOwner(f.source.historyOwners.value.single { it.selected }); s4Frames(e, 10)
                assertIs<CallHistoryState.Failure>(f.source.history.value)
                assertTrue(s4HasText(e, "无法读取通话记录"))
                val snapshots = f.first.snapshotRequests.size to f.second.snapshotRequests.size
                val selected = f.source.lines.value.selectedOwner
                s4Tap(e, s4Action(e, "重试").boundsInRoot.center); s4Frames(e, 40)
                assertEquals(OWNER_A, assertIs<CallHistoryState.Ready>(f.source.history.value).owner)
                assertFalse(s4HasText(e, "无法读取通话记录"))
                assertEquals(selected, f.source.lines.value.selectedOwner)
                assertEquals(snapshots, f.first.snapshotRequests.size to f.second.snapshotRequests.size)
                assertEquals(1, f.repository.upsertCount)
            }
        } finally { f.source.close(); advanceUntilIdle() }
    } }

    @Composable private fun App(calls: CallSource, route: AppRoute.Subpage? = null,
        onDial: ((CallBinding, String) -> Unit)? = null, messages: MessageSource? = null) {
        val back = remember { object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() } }
        DisposableEffect(back) { onDispose { back.navigationEventDispatcher.dispose() } }
        val window = remember { object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(420, 900)
            override val containerDpSize = DpSize(420.dp, 900.dp)
        } }
        val sources = remember(calls, messages) { SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
            FixtureOperationalLogSource("empty"), messages ?: FixtureMessageSource("empty"), calls) }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalWindowInfo provides window) {
            SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.CALLS, initialSubpage = route,
                appearanceSource = remember { InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false)) },
                onCallDial = onDial, forceDark = false)
        }
    }

    private fun actionIncludingDisabled(e: MotionTestEnvironment, label: String) = s4Nodes(e, false).single {
        it.config.contains(SemanticsActions.OnClick) && s4Matches(it, label) &&
            it.boundsInRoot.center.x in 0f..420f && it.boundsInRoot.center.y in 0f..900f
    }
}
