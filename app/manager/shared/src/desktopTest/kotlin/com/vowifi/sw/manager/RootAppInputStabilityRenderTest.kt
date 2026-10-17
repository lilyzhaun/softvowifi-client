@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class, androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.runtime.InternalComposeTracingApi::class)
package com.vowifi.sw.manager

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Diagnostic, not a performance budget: executes the real production App and destination provider. */
class RootAppInputStabilityRenderTest {
    @Test fun equalDraftValuesStillAdoptANewBackendConfirmationCallback() = onMotionTestThread { runTest {
        val owner = assertNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val state = MessageUiState.Ready(emptyList(), com.vowifi.sw.manager.model.MessageOrigin.ENGINE,
            composerEnabled = true, ownerId = owner)
        val calls = mutableListOf<String>()
        val backend = mutableStateOf<(SimOwnerId) -> Boolean>({ calls += "old"; false })
        var shown: BoundMessageDraft? = null
        MotionTestEnvironment(testScheduler, 420, 900) {
            val draft = rememberBoundMessageDraft(state, false, backend.value)
            SideEffect { shown = draft }
        }.use { e ->
            fun frames() { repeat(2) { e.advanceFrameBy(16) } }
            frames()
            val initial = assertNotNull(shown)
            assertNull(initial.owner)
            initial.draft.updateRecipient("12025550123")
            initial.draft.updateBody("synthetic unassigned draft")
            frames()
            val rejected = assertNotNull(shown)
            assertEquals("12025550123", rejected.draft.recipient)
            assertEquals("synthetic unassigned draft", rejected.draft.body)
            assertFalse(rejected.confirmOwner(owner), "真实确认拒绝时不可转绑草稿")
            frames()
            assertNull(assertNotNull(shown).owner)
            assertEquals("synthetic unassigned draft", assertNotNull(shown).draft.body)
            backend.value = { calls += "new"; true }
            frames()
            val accepted = assertNotNull(shown)
            assertNotSame(rejected, accepted, "字段同值但后端callback变化时必须换包装")
            assertTrue(accepted.confirmOwner(owner))
            frames()
            assertEquals(owner, assertNotNull(shown).owner)
            assertEquals("12025550123", assertNotNull(shown).draft.recipient)
            assertEquals("synthetic unassigned draft", assertNotNull(shown).draft.body)
            rejected.clearUnbound()
            frames()
            assertEquals("synthetic unassigned draft", assertNotNull(shown).draft.body,
                "旧未绑定回调必须读取最新ownerWire，不能清掉已绑定草稿")
            assertEquals(listOf("old", "new"), calls)
        }
    } }

    @Test fun realAppKeepsEquivalentInputsWhileBusinessAndRapidDragsRemainLive() =
        checkRealAppInputs(AppearanceSettings.Default.copy(
            floatingNavigation = false, blurRequested = false, liquidGlassRequested = false), false)

    @Test fun realAppDeviceLiquidChromeKeepsBusinessAndRapidDragsLive() =
        checkRealAppInputs(AppearanceSettings.Default.copy(
            floatingNavigation = true, blurRequested = true, liquidGlassRequested = true,
            predictiveBackRequested = false), true)

    private fun checkRealAppInputs(settings: AppearanceSettings, blurSupported: Boolean) = onMotionTestThread {
        val tracerField = Class.forName("androidx.compose.runtime.ComposerKt")
            .getDeclaredField("compositionTracer").apply { isAccessible = true }
        val previous = tracerField.get(null) as CompositionTracer?
        val trace = InputTrace()
        Composer.setTracer(trace)
        try {
            val dispatcher = StandardTestDispatcher()
            runTest(dispatcher, timeout = 45.seconds) {
                val lifecycle = object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                }
                val fixture = FixtureMessageSource("ready")
                val messageState = MutableStateFlow(fixture.state.value)
                val messages = object : MessageSource by fixture { override val state = messageState }
                val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
                    FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
                    FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"), messages, FixtureCallSource("idle"))
                val appearance = InMemoryAppearanceSettingsSource(settings)
                var table: CompositionData? = null
                MotionTestEnvironment(testScheduler, 420, 900, dispatcher = dispatcher) {
                    currentComposer.collectParameterInformation()
                    table = currentComposer.compositionData
                    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                        SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.CALLS,
                            appearanceSource = appearance, blurSupported = blurSupported)
                    }
                }.use { e ->
                    fun frame(count: Int = 1) { repeat(count) { e.advanceFrameBy(16) } }
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
                        return tabs().mapIndexedNotNull { i, n -> i.takeIf { n.config[SemanticsProperties.Selected] } }.single()
                    }
                    fun page() = nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                        .config[SemanticsProperties.HorizontalScrollAxisRange].value() / 420f
                    fun bundles(): Pair<SoftVoWiFiContentState, SoftVoWiFiContentActions>? {
                        fun visit(data: CompositionData): List<CompositionGroup> = data.compositionGroups.flatMap { listOf(it) + visit(it) }
                        val data = visit(requireNotNull(table)).flatMap { it.data.toList() }
                        fun captured(value: Any?, depth: Int): List<Any> {
                            if (value == null || depth == 0) return emptyList()
                            if (value is SoftVoWiFiContentState || value is SoftVoWiFiContentActions) return listOf(value)
                            val name = value.javaClass.name
                            if (!name.startsWith("androidx.compose.runtime.internal.ComposableLambda") &&
                                !name.startsWith("com.vowifi.sw.manager.SoftVoWiFiAppKt")) return emptyList()
                            return value.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                                .flatMap { field -> field.isAccessible = true; captured(field.get(value), depth - 1) }
                        }
                        val values = data.flatMap { captured(it, 4) }
                        return values.filterIsInstance<SoftVoWiFiContentState>().firstOrNull()?.let { state ->
                            values.filterIsInstance<SoftVoWiFiContentActions>().firstOrNull()?.let { state to it }
                        }
                    }
                    frame(40)
                    if (settings.liquidGlassRequested) {
                        assertTrue(trace.calls("warm", "com.vowifi.sw.manager.ui.LiquidIndicator") > 0,
                            "前提：真实App必须实际组合液态底栏，不能落回不透明替身")
                        assertTrue(trace.calls("warm", "top.yukonga.miuix.kmp.blur.rememberLayerBackdrop") > 0,
                            "前提：真实材质层必须实际创建")
                    }
                    assertEquals(1f, page(), .01f); assertEquals(1, selected())
                    val previews = (messageState.value as MessageUiState.Ready).records.map { it.preview }
                    assertFalse(previews.any(::hasText), "前提：非活动短信页不暴露会话行语义")
                    val initial = bundles()
                    println("ROOT_INPUT_SLOTS|available=${initial != null}")
                    trace.phase = "swipe"
                    val snapshots = mutableListOf<Pair<SoftVoWiFiContentState, SoftVoWiFiContentActions>>()
                    val positions = mutableListOf<Float>()
                    listOf(true, false, true, false, true, false).forEachIndexed { index, left ->
                        val from = if (left) 350f else 70f
                        val to = if (left) 70f else 350f
                        val before = page()
                        e.press(Offset(from, 390f)); frame()
                        repeat(10) { step -> e.move(Offset(from + (to - from) * (step + 1) / 10f, 390f)); frame() }
                        e.release(Offset(to, 390f)); frame()
                        repeat(4) { e.advanceFrameBy(0) }
                        val after = page()
                        assertTrue(if (left) after > before + .1f else after < before - .1f,
                            "前提：第${index + 1}次反向实际立即移动，before=$before after=$after")
                        positions += after
                        val target = selected()
                        assertTrue(target in 0..3, "前提：未停稳fling目标仍须为真实根页")
                        assertEquals(target == 2, previews.any(::hasText), "前提：真实短信行active随Selected交接")
                        bundles()?.let(snapshots::add)
                    }
                    frame(60)
                    assertEquals(page().roundToInt(), selected())
                    assertEquals(page().roundToInt().toFloat(), page(), .01f)
                    val all = listOfNotNull(initial) + snapshots
                    assertNotNull(initial, "前提：真实App参数必须可从生产组合记录读取")
                    assertEquals(6, snapshots.size, "前提：六次真实指针后均记录生产参数")
                    all.zipWithNext().forEachIndexed { index, (a, b) ->
                        val unequal = SoftVoWiFiContentActions::class.java.declaredFields.filterNot {
                            java.lang.reflect.Modifier.isStatic(it.modifiers)
                        }.filter { field -> field.isAccessible = true; field.get(a.second) != field.get(b.second) }.map { it.name }
                        println("ROOT_INPUT_EQUAL|sample=$index|stateEq=${a.first == b.first}|stateSame=${a.first === b.first}" +
                            "|actionsEq=${a.second == b.second}|actionsSame=${a.second === b.second}|unequalFields=${unequal.joinToString(",")}")
                    }
                    println("ROOT_INPUT_GESTURES|positions=${positions.joinToString(",")}|selected=${selected()}")
                    trace.phase = "business"
                    requireNotNull(tabs()[2].config[SemanticsActions.OnClick].action).invoke(); frame(60)
                    assertEquals(2, selected()); assertEquals(2f, page(), .01f)
                    assertTrue(previews.any(::hasText), "前提：真实短信会话已呈现")
                    val old = messageState.value as MessageUiState.Ready
                    val marker = "根输入业务更新"
                    messageState.value = old.copy(records = listOf(MessageRecord.Incoming("input-diagnostic", "+12025550123", marker, 1L)))
                    frame(40)
                    assertTrue(hasText(marker), "真实StateFlow更新必须到达短信UI，不能冻结输入")
                    val equalStatePairs = all.zipWithNext().filter { (a, b) -> a.first == b.first }
                    assertTrue(equalStatePairs.isNotEmpty(), "前提：至少一次切根时真实业务输入未变")
                    assertTrue(equalStatePairs.all { (a, b) -> a.first === b.first },
                        "真实App不能为等值业务快照反复构造不稳定的页面输入")
                    val equivalentActionPairs = equalStatePairs.filter { (a, b) ->
                        val x = a.second.messageDraft; val y = b.second.messageDraft
                        x.owner == y.owner && x.selectionNotice == y.selectionNotice &&
                            x.draft.recipient == y.draft.recipient && x.draft.body == y.draft.body
                    }
                    assertTrue(equivalentActionPairs.all { (a, b) -> a.second === b.second },
                        "未编辑草稿或更换后端回调的切根不能重建整页动作输入")
                }
            }
        } finally { trace.dump(); Composer.setTracer(previous) }
    }

    private class InputTrace : CompositionTracer {
        var phase = "warm"
        private val counts = sortedMapOf<String, Int>()
        private val sourceCalls = sortedMapOf<Pair<String, String>, Int>(compareBy({ it.first }, { it.second }))
        override fun isTraceInProgress() = true
        override fun traceEventEnd() = Unit
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (")
            if (name.startsWith("com.vowifi.sw.manager.") ||
                name.startsWith("top.yukonga.miuix.kmp.blur.") ||
                name.startsWith("androidx.compose.ui.graphics.vector.")) {
                val source = phase to name
                sourceCalls[source] = sourceCalls.getOrDefault(source, 0) + 1
            }
            if (name in setOf("com.vowifi.sw.manager.SoftVoWiFiApp", "com.vowifi.sw.manager.SoftVoWiFiDestinationContent",
                    "com.vowifi.sw.manager.ui.HomeScreen", "com.vowifi.sw.manager.ui.CallsScreen",
                    "com.vowifi.sw.manager.ui.MessagesScreen", "com.vowifi.sw.manager.ui.SettingsScreen")) {
                val tag = "$phase|$name|dirty1=$dirty1|dirty2=$dirty2"
                counts[tag] = counts.getOrDefault(tag, 0) + 1
            }
        }
        fun calls(phase: String, source: String) = sourceCalls[phase to source] ?: 0
        fun dump() {
            counts.forEach { (tag, count) -> println("ROOT_INPUT_TRACE|$tag|count=$count") }
            sourceCalls.forEach { (source, count) -> println("ROOT_INPUT_SOURCE|${source.first}|${source.second}|count=$count") }
        }
    }
}
