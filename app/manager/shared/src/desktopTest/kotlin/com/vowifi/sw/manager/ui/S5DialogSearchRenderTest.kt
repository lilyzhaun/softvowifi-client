// T7/T11：官方OverlayDialog退场、键盘输入与实际焦点；主机合同不替代Android设备证据。
// 仅两项增量，复用 MotionTestEnvironment/S4Calls helper；不改 CommunicationSheetLifecycleRenderTest 或15类保留守护。
// T7只验UI回调交接，不代表存储成功；取消按钮/遮罩/返回沿用既有取消守护，本项另覆盖真实Press移出取消。
// 迁入T11时：S4RootCommunicationEntryRenderTest 首段改为 withdraw(entry) { s4Tap(environment, searchButton("openSearch").boundsInRoot.center) }。
// 同时删除该段预先展开/“展开≠聚焦”/再次取entry的旧前提；末段返回当前通话也直接以展开搜索触发withdraw。
// 保留withdraw内C14退出0帧、旧position的Press不消费和全部像素/动作断言；不得把旧前提留下当禁自动focus守护。
// 键事件依据Compose 1.11.1/Apache-2.0 KeyEvent.skiko.kt导出工厂及TextFieldKeyInput.desktop.kt；仅opt-in，不反射internal。
// 来源：https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-desktop/1.11.1/ui-desktop-1.11.1-sources.jar
// 及https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.11.1/foundation-desktop-1.11.1-sources.jar；仅核API，不复制实现。
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.FixtureObserverLogSource
import com.vowifi.sw.manager.desktop.FixtureEngineControlSource
import com.vowifi.sw.manager.desktop.FixtureSimControlSource
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.desktop.FixtureEnvironmentDiagnosticsSource
import com.vowifi.sw.manager.desktop.FixtureOperationalLogSource
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.ui.testing.*
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.test.*
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text

class S5DialogSearchRenderTest {
    @Test fun expandedSearchReportsRealBlurAndReadmitsEntryBeforeTheNextRealInputTap() = onMotionTestThread {
        runTest {
            var searching by mutableStateOf(false); var query by mutableStateOf("合成查询")
            var reported by mutableStateOf(false); val focusEvents = mutableListOf<Boolean>()
            var opens = 0; var edits = 0; var closes = 0; var transfers = 0; var entryClicks = 0; var ime = false
            MotionTestEnvironment(testScheduler, 390, 844) {
                s4SmsHost {
                    val focus = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
                    val visibleIme = currentImeVisible(); SideEffect { ime = visibleIme }
                    Column(Modifier.fillMaxSize()) {
                        CommunicationSearchHeader("短信", "搜索短信", searching, query,
                            { opens++; searching = true }, { edits++; query = it }, { closes++; searching = false },
                            onFocusChanged = { focusEvents += it; reported = it })
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "转移焦点" }
                            .clickable { transfers++; focus.clearFocus(); keyboard?.hide() }) { Text("转移焦点") }
                        CommunicationEntryVisibility(!reported && !visibleIme) { hit ->
                            PhoneButton("新建短信", { entryClicks++ }, hitEnabled = hit)
                        }
                    }
                }
            }.use { e ->
                fun consumeNow() { repeat(4) { e.advanceFrameBy(0) } }
                fun editor() = s4Nodes(e, false).single { it.config.contains(SemanticsActions.SetText) }
                s4Frames(e, 40)
                val search = s4Actions(e, "搜索短信").single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
                s4Tap(e, search.boundsInRoot.center); consumeNow()
                val openedAt = testScheduler.currentTime
                e.advanceFrameBy(MotionTokens.DurBase.toLong()); consumeNow()
                assertEquals(MotionTokens.DurBase.toLong(), testScheduler.currentTime - openedAt,
                    "展开起点先交付真实首帧，仍按原200ms期限测焦点")
                assertTrue(searching); assertEquals("合成查询", query)
                assertTrue(editor().config.getOrNull(SemanticsProperties.Focused) == true, "前提：原输入已实际自动聚焦")
                assertEquals(true, focusEvents.last()); assertTrue(s4Actions(e, "新建短信").isEmpty())
                s4Tap(e, s4Action(e, "转移焦点").boundsInRoot.center); consumeNow()
                assertEquals(1, transfers); assertTrue(searching); assertEquals("合成查询", query)
                assertFalse(editor().config.getOrNull(SemanticsProperties.Focused) == true, "原生按钮须真正清除输入焦点")
                assertFalse(ime, "前提：输入法已收起；仍显示时C14必须继续隐藏")
                assertEquals(false, focusEvents.last(), "搜索仍展开不能被发布成假Focused")
                s4Action(e, "新建短信") // 检查真实生产入口重新准入，不执行业务。
                s4Tap(e, editor().boundsInRoot.center); consumeNow()
                assertTrue(editor().config.getOrNull(SemanticsProperties.Focused) == true)
                assertEquals(true, focusEvents.last()); assertTrue(s4Actions(e, "新建短信").isEmpty())
                assertEquals("合成查询", query); assertTrue(searching)
                assertEquals(1, opens); assertEquals(0, edits); assertEquals(0, closes); assertEquals(0, entryClicks)
            }
        }
    }

    @Test fun actualDialogCancellationAndInvalidatedConfirmDoNotLeakAcrossExitOrRouteReentry() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val cases = listOf("BUTTON" to 1f, "SCRIM" to 0f, "BACK" to 1f, "INVALIDATE" to 1f, "INVALIDATE" to 0f)
            for ((path, scale) in cases) {
                var requested by mutableStateOf(false); var active by mutableStateOf(true)
                var cancels = 0; var confirms = 0
                val input = object : NavigationEventInput() { fun back() = dispatchOnBackCompleted() }
                val back = object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = NavigationEventDispatcher().apply { addInput(input) }
                }
                val pulses = mutableListOf<HapticFeedbackType>()
                val haptics = object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { pulses += hapticFeedbackType }
                }
                val window = miuixConfirmationTestWindow(390, 844)
                try { MotionTestEnvironment(testScheduler, 390, 844, scale, dispatcher) {
                    // miuix 0.9.3 Scaffold的popup是content兄弟；Provider须同时包住原DialogLayout真实宿主。
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalWindowInfo provides window, LocalAppRouteActive provides active,
                        LocalHapticFeedback provides haptics) {
                        s4SmsHost {
                            CommunicationTextAction("打开确认", { requested = true })
                            if (requested) CommunicationConfirmation("合成确认", "仅验证宿主交接", "确认操作",
                                { cancels++; requested = false }, { confirms++; requested = false })
                        }
                    }
                }.use { e ->
                    var exitAt = -1L; var deadline = -1L
                    fun dialogs() = s4Nodes(e, false).filter { it.config.contains(SemanticsProperties.IsDialog) }
                    fun dialog() = dialogs().singleOrNull()
                    fun diagnostic() = "path=$path scale=$scale exitAt=$exitAt deadline=$deadline " +
                        "currentTime=${testScheduler.currentTime} cancel=$cancels confirm=$confirms visibleHost=${dialogs().size}"
                    fun consumeNow() {
                        val now = testScheduler.currentTime
                        var previous: Triple<List<Pair<Int, Rect>>, Int, Int>? = null
                        var stable = false
                        // Official DialogContentLayout and popup registration each apply a snapshot;
                        // drain both in this timestamp, without extending any animation deadline.
                        repeat(12) {
                            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                            testScheduler.runCurrent()
                            e.advanceFrameBy(0)
                            // Stabilize the actual confirmation, not unrelated focus-return nodes behind the popup.
                            val current = Triple(dialogs().map { it.id to it.boundsInRoot }, cancels, confirms)
                            stable = current == previous
                            previous = current
                        }
                        assertEquals(now, testScheduler.currentTime, "同刻泵不得改deadline：${diagnostic()}")
                        assertTrue(stable, "同刻真实pending布局/回调未稳定：${diagnostic()}")
                    }
                    val region = Rect(8f, 400f, 24f, 416f)
                    consumeNow(); val clear = s3SmallPatch(e, testScheduler, region)
                    s4Tap(e, s4Action(e, "打开确认").boundsInRoot.center); consumeNow()
                    if (scale != 0f) { s4Frames(e, 60); consumeNow() }
                    val bounds = assertNotNull(dialog(), "前提：必须由官方OverlayDialog实际呈现：${diagnostic()}").boundsInRoot
                    assertTrue(bounds.left > 0f && bounds.right < 390f && bounds.top >= 0f && bounds.bottom <= 844f)
                    val dimmed = s3SmallPatch(e, testScheduler, region)
                    assertTrue(s4Difference(clear, dimmed) >= 4, "前提：已知公共空白处须确有原宿主遮罩")
                    val point = when (path) {
                        "BUTTON" -> s4Action(e, "取消").boundsInRoot.center
                        "SCRIM" -> Offset(bounds.left / 2f, bounds.center.y)
                        else -> s4Action(e, "确认操作").boundsInRoot.center
                    }
                    if (path != "BACK") { e.press(point); e.advanceFrameBy(8); assertEquals(0, cancels + confirms) }
                    pulses.clear()
                    exitAt = testScheduler.currentTime
                    deadline = exitAt + if (scale == 0f) 0L else MIUIX_CONFIRMATION_EXIT_MILLIS
                    if (path == "BACK") input.back() else e.release(point)
                    if (path == "INVALIDATE" && scale == 0f) active = false
                    consumeNow()
                    if (path == "BACK") {
                        val enabled = s4Actions(e, "取消").count { !it.config.contains(SemanticsProperties.Disabled) }
                        println("T7-cancel exit ${diagnostic()} enabledCancel=$enabled rejectCount=${pulses.count { it == HapticFeedbackType.Reject }}")
                        assertEquals(listOf(HapticFeedbackType.Reject), pulses, "前提：真实dialog返回handler已接受关闭，不以root dispatch假定已处理")
                        assertEquals(0, enabled, "原时刻须实际撤销dialog动作，才能绑定本次退出首帧")
                        assertEquals(exitAt, testScheduler.currentTime, "BACK关闭准入只消费原时刻，不延长官方退场")
                    }
                    if (scale != 0f) {
                        assertNotNull(dialog(), "原宿主须先退出而非直接释放：$path")
                        assertEquals(0, cancels + confirms, "真实宿主退出完成之前不可交接：$path")
                    }
                    if (path == "INVALIDATE") {
                        if (scale != 0f) {
                            // 只在真实退出宿主还在时重复原确认点，随后撤销route；绝不调用保存的回调。
                            e.press(point); e.release(point); active = false; consumeNow()
                            assertEquals(0, cancels + confirms)
                        }
                        active = true; consumeNow() // 重入不能使已失活的旧确认复活。
                    }
                    repeat((MIUIX_CONFIRMATION_EXIT_MILLIS / 16 + 2).toInt()) {
                        consumeNow()
                        val done = dialog() == null && cancels == 1 && confirms == 0 &&
                            clear.pixels.contentEquals(s3SmallPatch(e, testScheduler, region).pixels)
                        if (!done && testScheduler.currentTime < deadline) {
                            e.advanceFrameBy(minOf(16L, deadline - testScheduler.currentTime)); consumeNow()
                        }
                        assertEquals(0, confirms, "取消或route失活后的确认不得迟发：${diagnostic()}")
                        if (dialog() != null) assertEquals(0, cancels, "取消也只能由实际宿主完成退场后交接：${diagnostic()}")
                    }
                    consumeNow()
                    if (path == "BACK") println("T7-cancel deadline ${diagnostic()}")
                    assertTrue(testScheduler.currentTime <= deadline, "不得延长退场deadline：${diagnostic()}")
                    assertNull(dialog(), diagnostic()); assertEquals(0, dialogs().size, diagnostic())
                    assertEquals(1, cancels, diagnostic()); assertEquals(0, confirms, diagnostic())
                    assertTrue(clear.pixels.contentEquals(s3SmallPatch(e, testScheduler, region).pixels), "终态不可残留遮罩：${diagnostic()}")
                    if (scale == 0f) assertEquals(exitAt, testScheduler.currentTime, "零动画同刻完成：${diagnostic()}")
                    e.advanceFrameBy(MotionTokens.DurBase.toLong()); consumeNow()
                    assertNull(dialog()); assertEquals(1, cancels); assertEquals(0, confirms, "跨旧截止仍不得迟发")
                } } finally { back.navigationEventDispatcher.dispose() }
            }
        }
    }

    @Test fun pointerConfirmationExitsOriginalDialogBeforeExactlyOneHandoffIncludingZeroMotion() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            for (scale in listOf(1f, 0f)) {
                var requested by mutableStateOf(false)
                val confirmedAt = mutableListOf<Long>(); var cancelled = 0
                val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
                val window = miuixConfirmationTestWindow(390, 844)
                val pulses = mutableListOf<HapticFeedbackType>()
                val haptics = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { pulses += hapticFeedbackType } }
                try { MotionTestEnvironment(testScheduler, 390, 844, scale, dispatcher) {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalWindowInfo provides window, LocalHapticFeedback provides haptics) {
                        s4SmsHost {
                            CommunicationTextAction("请求确认", { requested = true })
                            if (requested) CommunicationConfirmation("删除合成记录？", "只验证UI交接", "确认删除",
                                { cancelled++; requested = false }, { confirmedAt += testScheduler.currentTime; requested = false })
                        }
                    }
                }.use { e ->
                    fun consumeNow() {
                        var previous: List<Triple<Int, Rect, Boolean?>>? = null
                        var stable = false
                        repeat(12) {
                            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                            testScheduler.runCurrent()
                            e.advanceFrameBy(0)
                            val current = s4Nodes(e, false).map { Triple(it.id, it.boundsInRoot, it.config.getOrNull(SemanticsProperties.Focused)) }
                            stable = current == previous
                            previous = current
                        }
                        assertTrue(stable, "同刻pending布局/焦点未稳定；不能靠推进deadline掩盖")
                    }
                    fun dialog() = s4Nodes(e, false).singleOrNull { it.config.contains(SemanticsProperties.IsDialog) }
                    val dimRegion = Rect(8f, 400f, 24f, 416f)
                    val panelRegion = Rect(187f, 780f, 203f, 796f)
                    consumeNow(); val clear = s3SmallPatch(e, testScheduler, dimRegion)
                    val clearPanel = s3SmallPatch(e, testScheduler, panelRegion)
                    s4Tap(e, s4Action(e, "请求确认").boundsInRoot.center)
                    s4Frames(e, 60); consumeNow()
                    val initial = assertNotNull(dialog()).boundsInRoot
                    fun titlePosition() = s4Nodes(e).single {
                        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "删除合成记录？"
                    }.positionInRoot
                    val initialTitle = titlePosition()
                    assertTrue(initial.left > 0f && initial.right < 390f && initial.top > 422f && initial.bottom <= 844f)
                    assertEquals(195f, initial.center.x, 1f)
                    assertEquals(832f, initial.bottom, 1f, "沿官方手机底部12dp外边距，不再自绘居中")
                    val dimmed = s3SmallPatch(e, testScheduler, dimRegion)
                    assertTrue(s4Difference(clear, dimmed) >= 4, "前提：官方OverlayDialog必须确实绘出遮罩")
                    val point = s4Action(e, "确认删除").boundsInRoot.center
                    pulses.clear()
                    val outside = Offset(point.x, initial.top - 16f)
                    e.press(point); e.move(outside); e.release(outside); consumeNow()
                    assertTrue(confirmedAt.isEmpty() && cancelled == 0 && pulses.isEmpty(), "移出取消不能执行确认、取消回调或发触感")
                    e.press(point); e.advanceFrameBy(8); assertTrue(confirmedAt.isEmpty())
                    val exitAt = testScheduler.currentTime
                    e.release(point); consumeNow()
                    assertEquals(exitAt, testScheduler.currentTime, "先消费同刻pending布局，再按原释放时刻绑定deadline")
                    e.press(point); e.release(point); consumeNow()
                    if (scale != 0f) {
                        assertTrue(confirmedAt.isEmpty(), "T7：真实确认先启动原宿主退场，不能先执行外部回调")
                        e.advanceFrameBy(8); consumeNow()
                        val exiting = assertNotNull(dialog(), "原Dialog宿主必须保留实际退出绘制").boundsInRoot
                        val movingTitle = titlePosition()
                        assertTrue(movingTitle.y > initialTitle.y && movingTitle.y < 844f && movingTitle.x == initialTitle.x,
                            "官方手机退出须有真实可见的向下位移：initial=$initialTitle moving=$movingTitle host=$exiting")
                        val middle = s3SmallPatch(e, testScheduler, dimRegion)
                        assertTrue(!middle.pixels.contentEquals(clear.pixels) && !middle.pixels.contentEquals(dimmed.pixels), "须有真实遮罩退出中间帧")
                        assertTrue(confirmedAt.isEmpty(), "原层尚在退出时不得交接")
                    }
                    val deadline = exitAt + if (scale == 0f) 0L else MIUIX_CONFIRMATION_EXIT_MILLIS
                    repeat((MIUIX_CONFIRMATION_EXIT_MILLIS / 16 + 2).toInt()) {
                        val done = dialog() == null && confirmedAt.size == 1 && clear.pixels.contentEquals(s3SmallPatch(e, testScheduler, dimRegion).pixels)
                        if (!done && testScheduler.currentTime < deadline) {
                            e.advanceFrameBy(minOf(16L, deadline - testScheduler.currentTime)); consumeNow()
                        }
                        if (dialog() != null) assertTrue(confirmedAt.isEmpty(), "UI交接只能跟随真实宿主退出")
                    }
                    assertNull(dialog()); assertEquals(1, confirmedAt.size); assertEquals(0, cancelled)
                    assertTrue(confirmedAt.single() - exitAt in 0L..(deadline - exitAt))
                    assertTrue(clear.pixels.contentEquals(s3SmallPatch(e, testScheduler, dimRegion).pixels), "终态不得残留遮罩/旧宿主")
                    assertTrue(clearPanel.pixels.contentEquals(s3SmallPatch(e, testScheduler, panelRegion).pixels), "普通/零动画均回到同一原始底部区域")
                    assertEquals(listOf(HapticFeedbackType.Confirm), pulses, "§4.9：确认事件恰一次Confirm")
                } } finally { back.navigationEventDispatcher.dispose() }
            }
        }
    }

    @Test fun actualMessagesSearchAutofocusesAcceptsFirstPointerAndKeysAndClosesWithAnEmptyQuery() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val backend = CallAggregateFixture(backgroundScope.coroutineContext + dispatcher)
            val queries = mutableListOf<String>(); val original = FixtureMessageSource("empty")
            val messages = object : MessageSource by original { override fun searchHistory(query: String) { queries += query; original.searchHistory(query) } }
            val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
                FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
                FixtureOperationalLogSource("empty"), messages, backend.source)
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            val lifecycle = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED } }
            var returnedToCall = 0
            try { MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalNavigationEventDispatcherOwner provides back) {
                    SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES, forceDark = false,
                        onOpenCall = { returnedToCall++ })
                }
            }.use { e ->
                fun consumeNow() {
                    var previous: List<Triple<Int, Rect, Boolean?>>? = null
                    repeat(6) {
                        e.advanceFrameBy(0)
                        val current = s4Nodes(e, false).map { Triple(it.id, it.boundsInRoot, it.config.getOrNull(SemanticsProperties.Focused)) }
                        if (current == previous) return
                        previous = current
                    }
                    fail("同刻pending布局/焦点未稳定；不能靠推进deadline掩盖")
                }
                fun editor() = s4Nodes(e, false).single { it.config.contains(SemanticsActions.SetText) && !it.config.contains(SemanticsProperties.Disabled) }
                fun search() = s4Actions(e, "搜索短信").single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索短信") == true && !it.config.contains(SemanticsActions.SetText) }
                val keyboard = Canvas()
                fun typeCharacter(key: Key, character: Char) {
                    for ((id, type) in listOf(AwtKeyEvent.KEY_PRESSED to KeyEventType.KeyDown,
                        AwtKeyEvent.KEY_TYPED to KeyEventType.Unknown, AwtKeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
                        val native = AwtKeyEvent(keyboard, id, testScheduler.currentTime, 0,
                            if (id == AwtKeyEvent.KEY_TYPED) AwtKeyEvent.VK_UNDEFINED else character.code, character)
                        val consumed = e.scene.sendKeyEvent(KeyEvent(if (id == AwtKeyEvent.KEY_TYPED) Key.Unknown else key,
                            type, codePoint = character.code, nativeEvent = native))
                        if (id == AwtKeyEvent.KEY_TYPED) assertTrue(consumed, "真实KEY_TYPED须由当前输入处理")
                    }
                    consumeNow()
                }
                s4Frames(e, 80); consumeNow()
                assertTrue(hasRegisteredCommunicationEngine(backend.source.lines.value)); s4Action(e, "新建短信")
                queries.clear(); s4Tap(e, search().boundsInRoot.center); consumeNow()
                val openedAt = testScheduler.currentTime
                e.advanceFrameBy(MotionTokens.DurBase.toLong()); consumeNow()
                assertTrue(editor().config.getOrNull(SemanticsProperties.Focused) == true, "T11：真实展开自动聚焦，不能先由测试点击/手发FocusInteraction")
                assertEquals(MotionTokens.DurBase.toLong(), testScheduler.currentTime - openedAt)
                assertTrue(s4Actions(e, "新建短信").isEmpty(), "自动聚焦后C14入口不可留下动作")
                typeCharacter(Key.Seven, '7')
                assertEquals("7", editor().config[SemanticsProperties.EditableText].text); assertEquals(listOf("7"), queries)
                val bounds = editor().boundsInRoot
                assertTrue(bounds.width >= 48f && bounds.height >= 48f && bounds.left >= 0f && bounds.right <= 390f && bounds.top >= 0f && bounds.bottom <= 844f)
                val firstInputPoint = Offset(bounds.right - 20f, bounds.center.y)
                s4Tap(e, firstInputPoint); typeCharacter(Key.Eight, '8')
                assertTrue(editor().config.getOrNull(SemanticsProperties.Focused) == true)
                assertEquals("78", editor().config[SemanticsProperties.EditableText].text, "输入框首点及其后真实键入不得被旧底栏吞掉")
                assertEquals(listOf("7", "78"), queries); assertEquals(0, returnedToCall)
                s4Tap(e, s4Action(e, "取消").boundsInRoot.center); consumeNow()
                assertFalse(s4Nodes(e).any { it.config.contains(SemanticsActions.SetText) && it.config.getOrNull(SemanticsProperties.Focused) == true })
                assertEquals(listOf("7", "78", ""), queries, "关闭必须清空真实onSearchHistory查询，且每次值只交接一次")
                e.advanceFrameBy(MotionTokens.DurFast.toLong()); consumeNow()
                assertFalse(s4Nodes(e).any { it.config.contains(SemanticsActions.SetText) }, "DurFast后原搜索输入必须卸载")
                s4Tap(e, search().boundsInRoot.center); consumeNow(); e.advanceFrameBy(MotionTokens.DurBase.toLong()); consumeNow()
                assertTrue(editor().config.getOrNull(SemanticsProperties.Focused) == true)
                assertEquals("", editor().config[SemanticsProperties.EditableText].text)
                assertEquals(listOf("7", "78", ""), queries); assertEquals(0, returnedToCall)
            } } finally { backend.source.close(); back.navigationEventDispatcher.dispose() }
        }
    }
}
