@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** 真实App导航/子页壳；合成通话只证明主机，不冒充设备通信。 */
class OngoingCallNavigationRenderTest {
    @Test fun messageSubpageDraftAndBackRemainIntactAcrossNonNavigationRefresh() = onMotionTestThread { runTest {
        val routes = MutableSharedFlow<AppRoute>(extraBufferCapacity = 1)
        val calls = FixtureCallSource("idle")
        val sources = sources(calls)
        withScene(testScheduler, AppRoute.NewMessage, sources, routes = routes) { e, _, _ ->
            frames(e, 50)
            assertTrue(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "新建短信" } })
            // 宿主不发送路由由CallReturnStackHostTest独立证明；此处只验真实子页草稿/返回。
            val editor = nodes(e, false).first { it.config.contains(SemanticsActions.SetText) }
            editor.config[SemanticsActions.SetText].action!!(androidx.compose.ui.text.AnnotatedString("15550123"))
            frames(e, 3); calls.refresh(); frames(e, 3)
            assertTrue(nodes(e, false).any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "15550123" })
            tap(e, action(e, "返回").boundsInRoot.center); frames(e, 50)
            assertTrue(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "短信" } })
            assertFalse(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "通话详情" } })
        }
    } }

    @Test fun everySubpageShowsCallBelowToolbarAndWithdrawsOldActionOnEndIncludingZeroMotion() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) for (route in listOf(AppRoute.SettingsAbout, AppRoute.NewMessage)) {
            val live = MutableStateFlow<CallUiState>((FixtureCallSource("incall").state.value as CallUiState.Ready).copy(
                clock = CallSessionClock(192_000) { testScheduler.currentTime }))
            val calls = object : CallSource by FixtureCallSource("idle") { override val state = live; override fun refresh() = Unit }
            withScene(testScheduler, route, sources(calls), scale = scale) { e, _, opens ->
                println("ONGOING_SUBPAGE|route=${route::class.simpleName}|scale=$scale")
                frames(e, 60)
                assertEquals(CallPhase.InCall, (calls.state.value as CallUiState.Ready).phase)
                val title = when (route) { AppRoute.SettingsAbout -> "关于"; AppRoute.NewMessage -> "新建短信"; else -> "拨号" }
                val navigationTitles = nodes(e).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .map { it.text }.filter { it in listOf("关于", "新建短信", "拨号", "通话", "短信", "设置", "SoftVoWiFi", "电话") }
                assertTrue(title in navigationTitles, "前提：目标子页已呈现；route=${route::class.simpleName} scale=$scale visible=$navigationTitles")
                assertEquals(1, nodes(e).count(::isCallAction), "进行中通话必须在当前子页提供唯一返回通话按钮")
                val bar = callAction(e)
                assertTrue(bar.boundsInRoot.height >= 48f, "子页通话条命中区至少48dp")
                assertTrue(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text.contains("03:") } }, "子页必须显示真实计时")
                val old = bar.config[SemanticsActions.OnClick].action!!
                val center = bar.boundsInRoot.center
                e.press(center); frames(e, 8); e.move(Offset(0f, 820f)); e.release(Offset(0f, 820f)); frames(e, 25)
                assertEquals(0, opens[0], "移出取消不打开通话")
                tap(e, callAction(e).boundsInRoot.center); frames(e, 12)
                assertEquals(1, opens[0])
                // 正在退场时立即撤销旧回调，不能等待动画完成。
                live.value = (live.value as CallUiState.Ready).copy(phase = CallPhase.Idle)
                frames(e, 1); old(); assertEquals(1, opens[0])
                frames(e, 60)
                assertTrue(nodes(e).none { isCallAction(it) })
            }
        }
    } }

    @Test fun dialpadKeepsItsExistingLiveCallHandoffAndItsOwnSceneStillProvidesTheBar() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
            val ready = (FixtureCallSource("incall").state.value as CallUiState.Ready).copy(
                clock = CallSessionClock(192_000) { testScheduler.currentTime })
            val live = MutableStateFlow<CallUiState>(ready)
            val calls = object : CallSource by FixtureCallSource("idle") { override val state = live; override fun refresh() = Unit }
            withScene(testScheduler, AppRoute.Dialpad, sources(calls), scale = scale) { e, _, _ ->
                frames(e, 60)
                assertTrue(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "通话" } })
                assertTrue(nodes(e).none { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "拨号" } },
                    "原AppNavigator合同：拨号盘在非Idle通话出现时交接通话根，不修改此流程")
                assertEquals(CallPhase.InCall, (live.value as CallUiState.Ready).phase)
                callAction(e)
            }
            // Dialpad owns its toolbar instead of the shared scene toolbar. Verify its retained scene directly.
            val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED } }
            var opens = 0
            MotionTestEnvironment(testScheduler, 390, 844, scale, dispatcher = StandardTestDispatcher(testScheduler)) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalOngoingCall provides OngoingCallPresentation(ready) { opens++ }) {
                    SoftVoWiFiTheme(false) {
                        AppSubpageScene(AppRoute.Dialpad, appChromeSpec(AppearanceSettings.Default.copy(blurRequested = false), false), onBack = {}) {
                            PhoneDialerScreen("", {}, "测试线路", false, false, null, {}, {}, {})
                        }
                    }
                }
            }.use { e ->
                frames(e, 60)
                val heading = nodes(e).single { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "拨号" } }
                val bar = callAction(e)
                assertTrue(bar.boundsInRoot.top >= heading.boundsInRoot.bottom)
                assertTrue(bar.boundsInRoot.height >= 48f)
                tap(e, bar.boundsInRoot.center); frames(e, 2)
                assertEquals(1, opens)
            }
        }
    } }

    @Test fun rootClockTicksOnlyWhileMainResumed() = onMotionTestThread { runTest {
        val live = MutableStateFlow<CallUiState>((FixtureCallSource("incall").state.value as CallUiState.Ready).copy(
            clock = CallSessionClock(192_000) { testScheduler.currentTime }))
        val calls = object : CallSource by FixtureCallSource("idle") { override val state = live; override fun refresh() = Unit }
        withScene(testScheduler, null, sources(calls)) { e, lifecycle, _ ->
            frames(e, 60)
            val before = callAction(e).config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue(before.contains("通话中") && before.contains("03:"), "根通话条描述必须含阶段和计时")
            e.advanceFrameBy(1100)
            val next = callAction(e).config[SemanticsProperties.ContentDescription].joinToString()
            assertNotEquals(before, next)
            lifecycle.currentState = Lifecycle.State.STARTED; frames(e, 2)
            val paused = callAction(e).config[SemanticsProperties.ContentDescription].joinToString()
            e.advanceFrameBy(2100)
            assertEquals(paused, callAction(e).config[SemanticsProperties.ContentDescription].joinToString(), "Main非RESUMED停止走表")
            lifecycle.currentState = Lifecycle.State.RESUMED; frames(e, 2)
            assertNotEquals(paused, callAction(e).config[SemanticsProperties.ContentDescription].joinToString(), "恢复立即读真实时长")
        }
    } }

    @Test fun callBarHeightMovesContentContinuouslyAndCanReverseBeforeSettling() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
            val ready = FixtureCallSource("incall").state.value as CallUiState.Ready
            val live = MutableStateFlow<CallUiState>(ready.copy(phase = CallPhase.Idle))
            val calls = object : CallSource by FixtureCallSource("idle") { override val state = live; override fun refresh() = Unit }
            withScene(testScheduler, AppRoute.NewMessage, sources(calls), scale = scale) { e, _, _ ->
                fun inputTop() = nodes(e, false).first { it.config.contains(SemanticsActions.SetText) }.boundsInRoot.top
                frames(e, 40)
                val initial = inputTop()
                live.value = ready.copy(phase = CallPhase.Dialing); frames(e, 1)
                val heights = mutableListOf<Float>()
                repeat(8) { frames(e, 1); heights += inputTop() }
                assertTrue(heights.last() > initial + 16f, "真实顶部内距必须让正文让位")
                if (scale != 0f) assertTrue(heights.distinct().size > 2, "正常动效必须存在不同高度中帧，不得瞬跳")
                val rawBefore = inputTop()
                fun consumeCurrentLayout() {
                    var previous = inputTop()
                    repeat(6) {
                        e.advanceFrameBy(0)
                        val current = inputTop()
                        if (current == previous) return
                        previous = current
                    }
                    fail("同刻顶部内距布局必须收敛，不能推进时钟掩盖")
                }
                consumeCurrentLayout()
                val beforeReverse = inputTop()
                live.value = ready.copy(phase = CallPhase.Idle)
                consumeCurrentLayout()
                println("ONGOING_HEIGHT|scale=$scale|time=${testScheduler.currentTime}|rawBefore=$rawBefore|settledBefore=$beforeReverse|afterReverse=${inputTop()}")
                if (scale != 0f) assertEquals(beforeReverse, inputTop(), .5f, "同刻反向不能跳高度；后继弹簧允许继承原速度")
                frames(e, 1)
                frames(e, 2); live.value = ready.copy(phase = CallPhase.Dialing); frames(e, 60)
                val full = inputTop()
                assertTrue(full >= initial + 48f)
                val bar = callAction(e)
                assertTrue(bar.boundsInRoot.bottom <= full, "条位于顶栏下/输入内容上方，不是覆盖层")
                live.value = ready.copy(phase = CallPhase.Idle); frames(e, 60)
                assertEquals(initial, inputTop(), .5f, "退场后不残留顶部空洞")
            }
        }
    } }

    @Test fun durationStateInvalidatesOnlyTextReaderNotItsOwningShell() = onMotionTestThread { runTest {
        var shell = 0; var text = 0
        val ready = (FixtureCallSource("incall").state.value as CallUiState.Ready).copy(clock = CallSessionClock(192_000) { testScheduler.currentTime })
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED } }
        MotionTestEnvironment(testScheduler, 390, 844, dispatcher = StandardTestDispatcher(testScheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { SoftVoWiFiTheme(false) {
                val duration = rememberOngoingCallDuration(ready, true)
                SideEffect { shell++ }; OngoingDurationReaderProbe(duration) { text++ }
            } }
        }.use { e ->
            frames(e, 2); val before = shell to text
            repeat(3) { e.advanceFrameBy(1000) }
            assertEquals(before.first, shell, "走表不重组持有时钟的外壳")
            assertTrue(text >= before.second + 3, "文字消费者必须实际读到逐秒新值")
        }
    } }

    private fun sources(calls: CallSource) = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
        FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
        FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), calls)

    private fun withScene(scheduler: TestCoroutineScheduler, route: AppRoute.Subpage?, sources: SoftVoWiFiSources,
        scale: Float = 1f, routes: MutableSharedFlow<AppRoute> = MutableSharedFlow(),
        block: (MotionTestEnvironment, LifecycleRegistry, IntArray) -> Unit) {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED } }
        val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
        val window = object : WindowInfo { override val isWindowFocused = true; override val containerSize = IntSize(390, 844); override val containerDpSize = DpSize(390.dp, 844.dp) }
        val opens = intArrayOf(0)
        MotionTestEnvironment(scheduler, 390, 844, scale, dispatcher = StandardTestDispatcher(scheduler)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalNavigationEventDispatcherOwner provides back, LocalWindowInfo provides window) {
                SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES, initialSubpage = route,
                    appearanceSource = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(blurRequested = false)),
                    incomingRoutes = routes, forceDark = false, onOpenCall = { opens[0]++ })
            }
        }.use { block(it, owner.lifecycle, opens) }
    }
    private fun frames(e: MotionTestEnvironment, count: Int) { repeat(count) { e.advanceFrameBy(16) } }
    private fun tap(e: MotionTestEnvironment, point: Offset) { e.press(point); e.advanceFrameBy(8); e.release(point) }
    private fun nodes(e: MotionTestEnvironment, merged: Boolean = true): List<SemanticsNode> {
        fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
        return e.scene.semanticsOwners.flatMap { visit(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode) }
    }
    private fun isCallAction(n: SemanticsNode) = n.config.contains(SemanticsActions.OnClick) &&
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains("返回通话") || it == "返回当前通话" }
    private fun callAction(e: MotionTestEnvironment) = nodes(e).filter { isCallAction(it) && it.boundsInRoot.center.x in 0f..390f }.single()
    private fun action(e: MotionTestEnvironment, label: String) = nodes(e).single {
        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) }
}

/** Top-level restart scope, matching production; local composable functions are inlined into their caller. */
@Composable private fun OngoingDurationReaderProbe(value: State<String>, onCommit: () -> Unit) {
    val current = value.value
    SideEffect(onCommit)
    PhoneText(current)
}
