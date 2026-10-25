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
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.*

class CallHistoryActionsAndRetryRenderTest {
    @Test fun realAppDetailSharesListAuthorizationAndClipboardPreservesTheGlobalPrefix() = onMotionTestThread { runTest {
        val f = CallAggregateFixture(coroutineContext)
        runCurrent()
        try {
            f.repository.upsert(OWNER_A, historyWrite(peer = "+12025550123", endedAtMs = T0 + 1000, outcome = CallOutcome.REJECTED))
            f.source.selectLine(OWNER_A)
            runCurrent()
            val handle = callHistoryGroups(assertIs<CallHistoryState.Ready>(f.source.history.value)).single().handle
            val dials = mutableListOf<Pair<CallBinding, String>>()
            var hasDial by mutableStateOf(true)
            var entry: ClipEntry? = null
            val clipboard = object : Clipboard {
                override val nativeClipboard: Any = this
                override suspend fun getClipEntry() = entry
                override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
            }
            MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    App(f.source, AppRoute.CallHistoryDetail(handle), if (hasDial) { binding, number -> dials += binding to number } else null)
                }
            } }.use { e ->
                s4Frames(e, 60)
                val call = s4Action(e, "回拨")
                assertFalse(call.config.contains(SemanticsProperties.Disabled))
                val old = call.config[SemanticsActions.OnClick].action!!
                val binding = assertNotNull(f.source.lines.value.outgoingBinding)
                s4Tap(e, call.boundsInRoot.center); s4Frames(e, 2)
                assertEquals(listOf(binding to "+12025550123"), dials, "详情走真实App的同一onCallDial入口")
                s4Tap(e, s4Action(e, "复制号码").boundsInRoot.center); s4Frames(e, 8)
                assertEquals("+12025550123", (entry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor))
                assertTrue(s4HasText(e, "已复制号码"))

                f.source.selectLine(OWNER_B)
                f.source.selectHistoryOwner(f.source.historyOwners.value.single { it.owner == OWNER_A })
                old(); assertEquals(1, dials.size, "旧回调不得向新选线路拨号")
                s4Frames(e, 30)
                assertTrue(actionIncludingDisabled(e, "回拨").config.contains(SemanticsProperties.Disabled))
                assertTrue(s4HasText(e, "请先选择此记录所属线路后回拨。"))
                assertFalse(s4Action(e, "复制号码").config.contains(SemanticsProperties.Disabled))

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

    @Test fun unknownPeerDoesNotOfferCallbackOrCopy() = onMotionTestThread { runTest {
        val history = CallHistoryState.Ready(listOf(historyWrite(peer = null, direction = CallDirection.INCOMING,
            endedAtMs = T0 + 1000, outcome = CallOutcome.MISSED).toRecord(OWNER_A)), OWNER_A)
        val handle = callHistoryGroups(history).single().handle
        MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
            CallHistoryDetailScreen(handle, history, { _, _ -> })
        } }.use { e ->
            s4Frames(e, 30)
            assertTrue(s4HasText(e, "未知号码"))
            assertFalse(s4HasText(e, "回拨")); assertFalse(s4HasText(e, "复制号码"))
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
        onDial: ((CallBinding, String) -> Unit)? = null) {
        val back = remember { object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() } }
        DisposableEffect(back) { onDispose { back.navigationEventDispatcher.dispose() } }
        val window = remember { object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(420, 900)
            override val containerDpSize = DpSize(420.dp, 900.dp)
        } }
        val sources = remember(calls) { SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
            FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), calls) }
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
