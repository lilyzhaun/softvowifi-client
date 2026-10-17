@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class, androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.runtime.InternalComposeTracingApi::class)
package com.vowifi.sw.manager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Real production App/projection/modifier contracts; synthetic business inputs, no timing budget. */
class RootHistoryProjectionStabilityRenderTest {
    @Test fun unchangedHistoryLeavesStayStableWhileRootActionsAndBusinessRemainLive() = onMotionTestThread {
        val tracerField = Class.forName("androidx.compose.runtime.ComposerKt")
            .getDeclaredField("compositionTracer").apply { isAccessible = true }
        val previous = tracerField.get(null) as CompositionTracer?
        val trace = HistoryTrace()
        Composer.setTracer(trace)
        try {
            val dispatcher = StandardTestDispatcher()
            runTest(dispatcher, timeout = 45.seconds) {
                val owner = assertNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
                val binding = CallBinding("synthetic-history-line", null, 1)
                val records = (1..5).map { index ->
                    val start = 1788600000000L - index * 10000L
                    CallRecord(CallDirection.OUTGOING, CallPeerPhone.parse("1555010$index"), start,
                        start + 1000, start + 61000, 60, CallOutcome.COMPLETED,
                        CallHistoryRecordId("synthetic-history-$index"))
                }
                val original = CallHistoryState.Ready(records, owner)
                val historyState = MutableStateFlow<CallHistoryState>(original)
                val callState = MutableStateFlow<CallUiState>(CallUiState.Ready(
                    CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE, binding = binding))
                val lineState = MutableStateFlow(CallLineState(listOf(CallLineChoice(owner, "合成线路", true,
                    true, true, true, true, running = true)), true, owner, binding))
                var backendDials = 0
                var backendDeletes = 0
                val calls = object : CallSource by FixtureCallSource("idle") {
                    override val state = callState
                    override val history = historyState
                    override val lines = lineState
                    override fun dial(expected: CallBinding, number: String) {
                        assertSame(binding, expected); backendDials++
                    }
                    override fun deleteHistory(expected: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>) {
                        backendDeletes++
                    }
                }
                val messages = FixtureMessageSource("ready")
                val previews = (messages.state.value as MessageUiState.Ready).records.map { it.preview }
                assertTrue(previews.isNotEmpty(), "前提：真实短信源至少有一个会话")
                assertEquals(5, callHistoryGroups(original).size)
                assertTrue(hasRegisteredCommunicationEngine(lineState.value))
                val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("running"),
                    FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
                    FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"), messages, calls)
                val lifecycle = object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                }
                val appearance = InMemoryAppearanceSettingsSource(AppearanceSettings.Default.copy(
                    floatingNavigation = true, blurRequested = true, liquidGlassRequested = true,
                    predictiveBackRequested = false))
                MotionTestEnvironment(testScheduler, 420, 900, dispatcher = dispatcher) {
                    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                        SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.CALLS,
                            appearanceSource = appearance, blurSupported = true, onCallDial = calls::dial)
                    }
                }.use { e ->
                    fun frames(count: Int = 1) { repeat(count) { e.advanceFrameBy(16) } }
                    fun nodes(): List<SemanticsNode> {
                        fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
                        return e.scene.semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
                    }
                    fun hasText(text: String) = nodes().any { n ->
                        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(text) } ||
                            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(text) }
                    }
                    fun tabs() = nodes().filter { it.config.contains(SemanticsProperties.Selected) &&
                        it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 &&
                        it.boundsInRoot.center.x in 0f..420f && it.boundsInRoot.top > 740f }
                    fun selected(): Int {
                        assertEquals(4, tabs().size, "前提：四个真实底栏")
                        return tabs().indices.single { tabs()[it].config[SemanticsProperties.Selected] }
                    }
                    fun page() = nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                        .config[SemanticsProperties.HorizontalScrollAxisRange].value() / 420f
                    fun row() = nodes().first { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
                    fun select(index: Int) {
                        assertTrue(requireNotNull(tabs()[index].config[SemanticsActions.OnClick].action).invoke())
                        frames(60); assertEquals(index, selected()); assertEquals(index.toFloat(), page(), .01f)
                    }
                    frames(40)
                    assertEquals(1, selected()); assertEquals(1f, page(), .01f)
                    assertTrue(hasText("15550101"), "前提：真实通话历史已呈现")
                    assertTrue(trace.calls("warm", "CallHistoryGroupContent") >= 5, "前提：五组真实展示叶已执行")
                    assertTrue(trace.calls("warm", "LiquidIndicator") > 0, "前提：实际液态底栏")
                    assertTrue(trace.calls("warm", "rememberLayerBackdrop") > 0, "前提：实际材质层")
                    val oldClick = requireNotNull(row().config[SemanticsActions.OnClick].action)
                    val oldCallback = row().config[SemanticsActions.CustomActions].single { it.label == "回拨" }
                    assertFalse(previews.any(::hasText), "前提：短信非活动时不暴露会话语义")
                    trace.phase = "navigation"
                    val positions = mutableListOf<Float>()
                    var inactiveChecks = 0
                    listOf(true, false, true, false, true, false).forEachIndexed { index, left ->
                        val from = if (left) 350f else 70f
                        val to = if (left) 70f else 350f
                        val before = page()
                        e.press(Offset(from, 390f)); frames()
                        repeat(10) { step -> e.move(Offset(from + (to - from) * (step + 1) / 10f, 390f)); frames() }
                        e.release(Offset(to, 390f)); frames(); repeat(4) { e.advanceFrameBy(0) }
                        val after = page()
                        assertTrue(if (left) after > before + .1f else after < before - .1f,
                            "前提：第${index + 1}次反向实际移动：$before -> $after")
                        positions += after
                        val target = selected()
                        assertEquals(target == 2, previews.any(::hasText), "前提：短信语义随活动根交接")
                        assertSame(original, historyState.value, "前提：导航不更改业务历史")
                        if (target != 1) {
                            oldClick(); assertFalse(oldCallback.action(), "失活旧回拨必须拒绝")
                            frames(2); assertEquals(target, selected(), "失活旧click不得导航")
                            assertEquals(0, backendDials); assertEquals(0, backendDeletes); inactiveChecks++
                        }
                    }
                    frames(60)
                    assertEquals(page().roundToInt(), selected())
                    select(2)
                    oldClick(); assertFalse(oldCallback.action()); frames(2)
                    assertEquals(2, selected()); assertEquals(0, backendDials); assertEquals(0, backendDeletes)
                    assertTrue(previews.any(::hasText)); inactiveChecks++
                    select(1)
                    val navigationLeaves = trace.calls("navigation", "CallHistoryGroupContent")
                    assertTrue(trace.calls("navigation", "CallHistoryGroupRow") > 0,
                        "前提：活动wrapper实际更新，不能冻结页面")
                    trace.phase = "business"
                    val changed = records.first()
                    historyState.value = CallHistoryState.Ready(listOf(CallRecord(changed.direction,
                        CallPeerPhone.parse("15550999"), changed.startedAtMs, changed.connectedAtMs,
                        changed.endedAtMs, changed.durationSec, changed.outcome, changed.recordId)) + records.drop(1), owner)
                    frames(40)
                    assertTrue(hasText("15550999"), "真实历史StateFlow更新必须立即到达UI")
                    assertFalse(hasText("15550101"), "旧业务字段必须消失")
                    assertTrue(trace.calls("business", "CallHistoryGroupContent") > 0, "业务变化必须更新展示叶")
                    assertTrue(row().config[SemanticsActions.CustomActions].single { it.label == "回拨" }.action(),
                        "重新激活后的真实回拨必须仍可用")
                    assertEquals(1, backendDials); assertEquals(0, backendDeletes)
                    println("ROOT_HISTORY_PRECONDITIONS|groups=5|positions=${positions.joinToString(",")}" +
                        "|inactiveChecks=$inactiveChecks|businessVisible=true|activeBackendDials=$backendDials")
                    assertEquals(0, navigationLeaves,
                        "固定业务历史的纯展示叶不应因根活动交接重复执行；wrapper和业务更新已独立验证")
                }
            }
        } finally { trace.dump(); Composer.setTracer(previous) }
    }

    @Test fun actualPlacementKeepsResourcesAcrossActiveOnlyChangesButReplacesThemForBusinessSelection() =
        onMotionTestThread { runTest {
            val active = mutableStateOf(true)
            val selection = mutableStateOf("synthetic-selection-a")
            val change = mutableStateOf(HistoryListChange<CallHistoryRecordId>(
                HistoryListScope(), 1, HistoryChangeReason.REPLACE, emptySet()))
            val displacement = mutableStateOf(0)
            val businessRows = listOf("synthetic-row")
            var table: CompositionData? = null
            MotionTestEnvironment(testScheduler, 420, 900) {
                currentComposer.collectParameterInformation(); table = currentComposer.compositionData
                CompositionLocalProvider(LocalAppRouteActive provides active.value) {
                    val rows = rememberCallsHistoryRows(businessRows, change.value, selection.value, LocalAppRouteActive.current,
                        key = { it }, ids = { emptySet<CallHistoryRecordId>() })
                    Column {
                        Spacer(Modifier.height(displacement.value.dp))
                        Box(Modifier.size(32.dp).callsHistoryPlacement(rows))
                    }
                }
            }.use { e ->
                fun resources(): Pair<Any, Any> {
                    repeat(2) { e.advanceFrameBy(16) }
                    fun visit(data: CompositionData): List<Any?> = data.compositionGroups.flatMap { it.data.toList() + visit(it) }
                    val identities = java.util.IdentityHashMap<Any, Boolean>()
                    visit(assertNotNull(table)).filterNotNull().forEach { identities[it] = true }
                    val values = identities.keys.toList()
                    val baseline = values.single { it.javaClass.simpleName == "CallsHistoryPlacementBaseline" }
                    val animation = values.filterIsInstance<androidx.compose.animation.core.Animatable<*, *>>().single()
                    return baseline to animation
                }
                val initial = resources()
                fun offset(resource: Any) = (resource as androidx.compose.animation.core.Animatable<*, *>).value as IntOffset
                displacement.value = 120
                change.value = change.value.copy(version = 2, reason = HistoryChangeReason.LIVE_MOVE)
                val moving = resources()
                assertSame(initial.first, moving.first)
                assertSame(initial.second, moving.second)
                assertTrue(offset(moving.second).y != 0, "前提：真实LIVE_MOVE和实际布局位移已产生在途动画")
                active.value = false; val inactive = resources()
                assertEquals(IntOffset.Zero, offset(inactive.second), "失活必须取消并清零已显示的位移")
                active.value = true; val reactivated = resources()
                repeat(8) { e.advanceFrameBy(16) }
                assertEquals(IntOffset.Zero, offset(reactivated.second), "再入不能恢复旧在途位移")
                selection.value = "synthetic-selection-b"; val changed = resources()
                assertNotSame(reactivated.first, changed.first, "前提：真实业务selection必须替换baseline")
                assertNotSame(reactivated.second, changed.second, "前提：真实业务selection必须替换Animatable")
                println("ROOT_HISTORY_RESOURCES|baselineActiveSame=${initial.first === inactive.first}" +
                    "|animationActiveSame=${initial.second === inactive.second}" +
                    "|baselineReturnSame=${initial.first === reactivated.first}" +
                    "|animationReturnSame=${initial.second === reactivated.second}|businessReplaced=true")
                assertSame(initial.first, inactive.first, "仅失活应保留baseline身份并另行取消/reset视觉状态")
                assertSame(initial.second, inactive.second, "仅失活不应重建Animatable")
                assertSame(initial.first, reactivated.first)
                assertSame(initial.second, reactivated.second)
            }
        } }

    private class HistoryTrace : CompositionTracer {
        var phase = "warm"
        private val counts = sortedMapOf<Pair<String, String>, Int>(compareBy({ it.first }, { it.second }))
        override fun isTraceInProgress() = true
        override fun traceEventEnd() = Unit
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            if (name in setOf("CallHistoryGroupContent", "CallHistoryGroupRow", "CallsScreen", "CallsHistoryRowMotion",
                    "LiquidIndicator", "rememberLayerBackdrop")) {
                val tag = phase to name
                counts[tag] = counts.getOrDefault(tag, 0) + 1
            }
        }
        fun calls(phase: String, name: String) = counts[phase to name] ?: 0
        fun dump() { counts.forEach { (tag, count) -> println("ROOT_HISTORY_TRACE|${tag.first}|${tag.second}|count=$count") } }
    }
}
