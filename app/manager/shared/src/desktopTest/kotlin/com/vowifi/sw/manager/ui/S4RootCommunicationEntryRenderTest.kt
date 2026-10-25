@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.*
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** 真实 App→destination→两根 scaffold；Final-pass 探针只观察，不转发或消费事件。 */
class S4RootCommunicationEntryRenderTest {
    @Test fun actualRootEntriesWithdrawInputImmediatelyAndRetainTheirExitDrawingForFocusOrUnavailableBusiness() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
        for (destination in listOf(AppDestination.CALLS, AppDestination.MESSAGES)) for (scale in listOf(1f, 0f)) {
            val backend = CallAggregateFixture(backgroundScope.coroutineContext + dispatcher)
            val inventory = backend.instances
            val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
                FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
                FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), backend.source)
            val lifecycle = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            val window = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize = IntSize(390, 844)
                override val containerDpSize = DpSize(390.dp, 844.dp)
            }
            val presses = mutableListOf<Pair<Offset, Boolean>>()
            var returnedToCall = 0
            // 排队执行，避免applyChanges中Unconfined即时执行LaunchedEffect重入Pager测量。
            val environment = MotionTestEnvironment(testScheduler, 390, 844, scale, dispatcher = dispatcher) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalWindowInfo provides window,
                    LocalNavigationEventDispatcherOwner provides back) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope { while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.type == PointerEventType.Press)
                                presses.add(event.changes.first().position to event.changes.any { it.isConsumed })
                        } }
                    }) {
                        SoftVoWiFiApp(sources, {}, false, "test", initialDestination = destination, forceDark = false,
                            onOpenCall = { returnedToCall++ })
                    }
                }
            }
            try {
                s4Frames(environment, 100)
                assertTrue(hasRegisteredCommunicationEngine(backend.source.lines.value), "前提：生产 aggregate 确认至少一路可用")
                val entry = if (destination == AppDestination.CALLS) "拨号盘" else "新建短信"
                val search = if (destination == AppDestination.CALLS) "搜索通话记录" else "搜索短信"
                fun searchButton(phase: String): SemanticsNode {
                    val candidates = s4Actions(environment, search).filter { !it.config.contains(SemanticsProperties.Disabled) }
                    // Messages 的搜索输入框也有同名 OnClick；这里只找原header按钮，不放宽全局唯一性。
                    if (candidates.size != 1) candidates.forEach { node ->
                        val ancestors = generateSequence(node.parent) { it.parent }.take(6).map {
                            "${it.id}:${it.config.getOrNull(SemanticsProperties.Role)}@${it.boundsInRoot}"
                        }.joinToString("/")
                        println("S4_SEARCH_CANDIDATE|destination=$destination|scale=$scale|phase=$phase|label=$search|id=${node.id}|role=${node.config.getOrNull(SemanticsProperties.Role)}|input=${node.config.contains(SemanticsActions.SetText)}|focused=${node.config.getOrNull(SemanticsProperties.Focused)}|bounds=${node.boundsInRoot}|ancestors=$ancestors")
                    }
                    val buttons = candidates.filter {
                        it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(search) == true &&
                            !it.config.contains(SemanticsActions.SetText)
                    }
                    assertEquals(1, buttons.size, "真实header搜索按钮须唯一；同名输入不算按钮，多根残留按钮仍须失败：$phase")
                    return buttons.single()
                }
                fun withdraw(label: String, awaitInputFocus: Boolean = false, trigger: () -> Unit) {
                    val target = s4Action(environment, label)
                    val point = target.boundsInRoot.center
                    // 先证明探针真实收到当前控件消费的Press，再移出取消，不执行入口业务。
                    presses.clear(); environment.press(point)
                    val livePress = assertNotNull(presses.lastOrNull(), "前提：Final-pass 探针必须确实收到生产入口Press")
                    assertTrue(livePress.second, "前提：当前真实入口必须消费自己的Press")
                    val outside = point + Offset(0f, -96f)
                    environment.move(outside); environment.release(outside); s4Frames(environment, 40)
                    s4Action(environment, label)
                    val faceTag = if (label == "返回通话") "ongoing-call-face" else "phone-face-$label"
                    val face = s4Nodes(environment).single { it.config.getOrNull(SemanticsProperties.TestTag) == faceTag }.boundsInRoot
                    val region = Rect(face.center.x - 16f, face.center.y - 16f, face.center.x + 16f, face.center.y + 16f)
                    val initial = s3SmallPatch(environment, testScheduler, region)
                    assertTrue(initial.pixels.distinct().size >= 4, "采样前提：32×32 内有实际入口图标/字形而非纯色空白")
                    fun inputFocused() = s4Nodes(environment, false).any {
                        it.config.contains(SemanticsActions.SetText) &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true &&
                            !it.config.contains(SemanticsProperties.Disabled)
                    }
                    fun consumeNow() { repeat(4) { environment.advanceFrameBy(0) } }
                    if (awaitInputFocus) assertFalse(inputFocused(), "前提：展开之前实际输入尚未聚焦")
                     trigger(); consumeNow()
                    // s4Tap自身含8ms持指；展开从真实Release/首帧开始，不能把按下时刻当200ms起点。
                    val triggerAt = testScheduler.currentTime
                    if (awaitInputFocus) {
                        // 已保存原point/face；只等待原自动聚焦，不把展开点击伪装成Focus事件。
                        val focusDeadline = triggerAt + if (scale == 0f) 0L else MotionTokens.DurBase.toLong()
                        repeat((MotionTokens.DurBase + 15) / 16) {
                            if (!inputFocused() && testScheduler.currentTime < focusDeadline) {
                                environment.advanceFrameBy(minOf(16L, focusDeadline - testScheduler.currentTime))
                                consumeNow()
                            }
                        }
                        assertTrue(inputFocused(), "前提：须在原200ms/zero同刻由真实requester取得焦点；destination=$destination scale=$scale openedAt=$triggerAt now=${testScheduler.currentTime}")
                        assertTrue(testScheduler.currentTime <= focusDeadline)
                    }
                    // MotionTestEnvironment的帧/协程共钟；Focus首次实际呈现的同刻就是C14退场起点。
                    val exitAt = testScheduler.currentTime
                    fun traceFrame(phase: String) {
                        val semanticFace = s4Nodes(environment).singleOrNull {
                            it.config.getOrNull(SemanticsProperties.TestTag) == faceTag
                        }?.boundsInRoot
                        val inputs = s4Nodes(environment, false).filter { it.config.contains(SemanticsActions.SetText) }
                            .map { it.boundsInRoot to (it.config.getOrNull(SemanticsProperties.Focused) == true) }
                        println("S4_ENTRY_FRAME|destination=$destination|scale=$scale|label=$label|phase=$phase|elapsed=${testScheduler.currentTime - exitAt}|originalFace=$face|semanticFace=$semanticFace|inputs=$inputs")
                    }
                    consumeNow()
                    assertEquals(exitAt, testScheduler.currentTime, "真实Focus或业务失效后0ms撤权，不延后退场采样")
                    traceFrame("afterTrigger")
                    assertTrue(s4Actions(environment, label).isEmpty(), "C14：退出首帧即时撤销触摸与动作语义，不能仅透明化或等退场完")
                    presses.clear()
                    environment.press(point)
                    assertTrue(presses.any { (position, consumed) -> position == point && !consumed },
                        "实际旧命中位置的Press必须不再被退出入口吞掉；point=$point observed=$presses")
                    environment.release(point)
                    traceFrame("afterOldHit")
                    // 真实回执中业务失效在 t32 尚为初始绘制（diff=0/1016），不能误判为已经卸载。
                    // 保留 t32 对照，另读有界16ms轨迹；不延迟生产UI、不改SpringSpatial或像素阈值。
                    val trajectory = List(5) {
                        environment.advanceFrameBy(); traceFrame("exitFrame${it + 1}")
                        assertTrue(s4Actions(environment, label).isEmpty(), "退出期间动作语义不可恢复")
                        (testScheduler.currentTime - exitAt) to s3SmallPatch(environment, testScheduler, region)
                    }
                    val middle = trajectory[1].second
                    s4Frames(environment, 50)
                    val terminal = s3SmallPatch(environment, testScheduler, region)
                    println("S4_ENTRY_EXIT|destination=$destination|scale=$scale|label=$label|sampleElapsed=32|region=$region|initialToEnd=${s4Difference(initial, terminal)}|middleToInitial=${s4Difference(middle, initial)}|middleToEnd=${s4Difference(middle, terminal)}")
                    assertTrue(s4Difference(initial, terminal) >= 4, "采样前提：该实际入口的终值确实不同")
                    if (scale == 0f) assertEquals(0, s4Difference(middle, terminal)) else {
                        trajectory.forEach { (elapsed, patch) ->
                            println("S4_ENTRY_TRAJECTORY|destination=$destination|scale=$scale|label=$label|elapsed=$elapsed|toInitial=${s4Difference(patch, initial)}|toEnd=${s4Difference(patch, terminal)}")
                        }
                        val intermediate = assertNotNull(trajectory.firstOrNull { (_, patch) ->
                            s4Difference(patch, initial) >= 2 && s4Difference(patch, terminal) >= 2
                        }, "C14：80ms内必须出现真实退出中间绘制，初始帧保留或直接卸载都不算中间态")
                        assertTrue(intermediate.first <= 80L)
                        assertTrue(trajectory.takeWhile { it.first < intermediate.first }.all { (_, patch) ->
                            s4Difference(patch, initial) < 2
                        }, "中间态前只允许保留初始绘制，不能先消失再弹回")
                    }
                    assertTrue(s4Actions(environment, label).isEmpty())
                    assertEquals(0, returnedToCall, "旧位置指针不得执行退出入口")
                    searchButton("afterWithdraw-$label")
                }
                s4Action(environment, entry)
                withdraw(entry, awaitInputFocus = true) { s4Tap(environment, searchButton("openSearch").boundsInRoot.center) }
                assertTrue(s4HasText(environment, if (destination == AppDestination.CALLS) "通话记录" else "短信"), "旧入口点击不能把 root 导航到子页")
                s4Tap(environment, s4Action(environment, "取消").boundsInRoot.center); s4Frames(environment, 60)
                s4Action(environment, entry)
                withdraw(entry) { backend.instances = emptyList(); backend.source.refresh() }
                assertFalse(hasRegisteredCommunicationEngine(backend.source.lines.value))
                backend.instances = inventory; backend.source.refresh(); s4Frames(environment, 100)
                s4Action(environment, entry)
                if (destination == AppDestination.MESSAGES) {
                    assertEquals(CallPhase.Idle, assertIs<CallUiState.Ready>(backend.source.state.value).phase)
                    // 公开事件须先建立同token来电，不能把Idle→IN_CALL当作Reducer已接受。
                    backend.first.emitIncoming(CALL_TOKEN, "RINGING"); s4Frames(environment, 2)
                    assertEquals(CallPhase.Ringing, assertIs<CallUiState.Ready>(backend.source.state.value).phase)
                    backend.first.emitIncoming(CALL_TOKEN, "IN_CALL"); s4Frames(environment, 100)
                    assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(backend.source.state.value).phase)
                    // Legacy fixture events carry no elapsed clock. Read the real snapshot before asserting duration.
                    backend.source.refresh(); s4Frames(environment, 10)
                    assertNotNull(assertIs<CallUiState.Ready>(backend.source.state.value).clock, "前提：权威IN_CALL快照已建立真实计时")
                    fun tabs() = s4Nodes(environment, false).filter {
                        it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && it.config.contains(SemanticsActions.OnClick)
                    }
                    val selectedTab = tabs().single { it.config.getOrNull(SemanticsProperties.Selected) == true }
                    if (!s4Matches(selectedTab, "短信")) {
                        assertTrue(s4Matches(selectedTab, "通话"), "前提：真实来电可依法自动导航到Calls")
                        val messagesTab = tabs().single { s4Matches(it, "短信") && !it.config.contains(SemanticsProperties.Disabled) }
                        s4Tap(environment, messagesTab.boundsInRoot.center); s4Frames(environment, 80)
                    }
                    assertTrue(s4Matches(tabs().single { it.config.getOrNull(SemanticsProperties.Selected) == true }, "短信"),
                        "须由真实底栏指针返回Messages，不手改navigationState")
                    assertFalse(s4Nodes(environment, false).any { it.config.contains(SemanticsActions.SetText) &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true }, "前提：Messages当前无输入焦点")
                    assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(backend.source.state.value).phase)
                    assertEquals(0, returnedToCall)
                    val callLabel = s4Action(environment, "返回通话").config[SemanticsProperties.ContentDescription].single()
                    assertTrue(callLabel.matches(Regex("通话中 [0-9:]{5,8} · 返回通话")), "根条须显示真实阶段与计时：actual=$callLabel")
                    withdraw("返回通话", awaitInputFocus = true) { s4Tap(environment, searchButton("openSearchWithCall").boundsInRoot.center) }
                }
            } finally { environment.close(); backend.source.close(); back.navigationEventDispatcher.dispose() }
        }
    } }

    // 约束缩高不是系统IME；真实Android inset拥有权仍需独立验证。
    @Test fun storedReadThreadFollowsConstraintShrinkOnlyWhenAlreadyAtLatestAndPreservesDraft() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 20.seconds) {
            val store = FakeMessageRepository()
            val wire = FakeEngineMessageTransport()
            val instance = EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)
            val owner = requireNotNull(ownerFor(instance.iccid)) // 既有fixture映射，不手填UI owner authority。
            val ids = (0 until 80).map { "s7-ime-$it" }
            ids.forEachIndexed { i, id ->
                store.insertIncomingIfAbsent(owner, id, id, "1555", "s7-row-$i", i.toLong(), 1)
            }
            store.markIncomingRead(requireNotNull(ObservedIncomingReadRequest.create(owner, "1555", ids)), 100)
            val messages = EngineMessagePersistenceFixtures.sourceOf(listOf(instance), wire, store)
            val calls = CallAggregateFixture(backgroundScope.coroutineContext + dispatcher, onlyFirst = true)
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            val lifecycle = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
            var availableHeight by mutableIntStateOf(844)
            val window = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize get() = IntSize(390, availableHeight)
                override val containerDpSize get() = DpSize(390.dp, availableHeight.dp)
            }
            var returnedToCall = 0
            val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
                FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
                FixtureOperationalLogSource("empty"), messages, calls.source)
            try {
                testScheduler.runCurrent()
                calls.first.emitIncoming(CALL_TOKEN, "RINGING")
                calls.first.emitIncoming(CALL_TOKEN, "IN_CALL")
                MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher) {
                    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalWindowInfo provides window,
                        LocalNavigationEventDispatcherOwner provides back) {
                        Box(Modifier.width(390.dp).height(availableHeight.dp)) {
                            SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES,
                                forceDark = false, onOpenCall = { returnedToCall++ })
                        }
                    }
                }.use { e ->
                    s4Frames(e, 100)
                    val tab = s4Nodes(e, false).single { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                        s4Matches(it, "短信") && it.config.contains(SemanticsActions.OnClick) }
                    s4Tap(e, tab.boundsInRoot.center); s4Frames(e, 80)
                    assertEquals(CallPhase.InCall, assertIs<CallUiState.Ready>(calls.source.state.value).phase)
                    val oldEntries = listOf("新建短信", "返回通话").map { s4Action(e, it).boundsInRoot.center }
                    val row = s4Nodes(e, false).single { it.config.contains(SemanticsActions.OnClick) && s4Matches(it, "s7-row-79") }
                    s4Tap(e, row.boundsInRoot.center); s4Frames(e, 80)
                    val thread = assertIs<MessageUiState.Ready>(messages.state.value)
                    assertEquals(owner, thread.ownerId)
                    assertTrue(thread.records.filterIsInstance<MessageRecord.Incoming>().all { it.isRead })
                    fun field() = s4Nodes(e, false).single { it.config.contains(SemanticsActions.SetText) &&
                        !it.config.contains(SemanticsProperties.Disabled) && it.boundsInRoot.width > 0 }
                    fun list(): SemanticsNode {
                        val candidates = s4Nodes(e, false).filter { it.config.contains(SemanticsActions.ScrollToIndex) &&
                         it.boundsInRoot.width > 0 && it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 390 &&
                         it.boundsInRoot.top >= 0 && it.boundsInRoot.bottom <= availableHeight }
                        assertEquals(1, candidates.size, "活动时间线viewport须唯一：${candidates.map { it.id to it.boundsInRoot }}")
                        return candidates.single()
                    }
                    val messageIdsByLabel = ids.mapIndexed { index, id -> "s7-row-$index" to id }.toMap()
                    fun historyLabel(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
                        ?.let(::stripMessageDisplayJoiners)
                    fun visibleHistoryLeaves(): List<SemanticsNode> {
                        val activeList = list()
                        val unmergedList = s4Nodes(e, true).single { it.id == activeList.id }
                        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                        val viewport = activeList.boundsInRoot
                        return descendants(unmergedList).filter { node ->
                            node.children.isEmpty() && historyLabel(node) in messageIdsByLabel &&
                                node.boundsInRoot.let { it.width > 0 && it.height > 0 && it.left >= viewport.left &&
                                    it.right <= viewport.right && it.top >= viewport.top && it.bottom <= viewport.bottom }
                        }
                    }
                    fun bubble(label: String) = visibleHistoryLeaves().singleOrNull { historyLabel(it) == label }
                    fun historyDiagnostic(phase: String) {
                        val matched = list(); val axis = matched.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                        val loaded = assertIs<MessageUiState.Ready>(messages.state.value).records.filterIsInstance<MessageRecord.Incoming>()
                        val indices = loaded.map { ids.indexOf(it.id) }
                        val leaves = visibleHistoryLeaves().map { historyLabel(it) to it.boundsInRoot }
                        println("S7_IME_HISTORY|phase=$phase|axis=${axis?.value?.invoke()}|max=${axis?.maxValue?.invoke()}|list=${matched.boundsInRoot}|" +
                            "loadedCount=${loaded.size}|firstIndex=${indices.firstOrNull()}|lastIndex=${indices.lastOrNull()}|" +
                            "focused=${field().config.getOrNull(SemanticsProperties.Focused)}|visible=$leaves")
                    }
                    historyDiagnostic("initial-latest")
                    assertNotNull(bubble("s7-row-79"), "前提：实际首次到底完成，不只检查lastVisibleIndex")
                    assertTrue(field().config[SemanticsActions.RequestFocus].action!!.invoke())
                    assertTrue(field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("synthetic draft")))
                    s4Frames(e, 24)
                    assertTrue(field().config[SemanticsActions.SetSelection].action!!.invoke(3, 7, false))
                    s4Frames(e, 2)
                    val draft = field().config[SemanticsProperties.EditableText]
                    val cursor = field().config[SemanticsProperties.TextSelectionRange]
                    assertEquals(TextRange(3, 7), cursor)
                    val requests = wire.requests.toList()
                    val beforeHeight = list().boundsInRoot.height
                    availableHeight = 540; s4Frames(e, 24)
                    assertTrue(list().boundsInRoot.height < beforeHeight - 200, "必须是真实约束缩高，不是改投影数值")
                    historyDiagnostic("latest-shrink")
                    val latest = assertNotNull(bubble("s7-row-79"), "E1：原在底部时缩高后最新气泡仍须完整可见")
                    assertTrue(latest.boundsInRoot.bottom <= list().boundsInRoot.bottom)
                    assertTrue(latest.boundsInRoot.bottom <= field().boundsInRoot.top, "真实最新气泡须完整位于composer输入上方")
                    assertTrue(s4Actions(e, "新建短信").isEmpty(), "根入口不能穿透子页")
                    val callBar = s4Action(e, "返回通话")
                    assertTrue(callBar.boundsInRoot.bottom < list().boundsInRoot.top, "子页顶部通话条保留，不能覆盖时间线或受IME隐藏")
                    oldEntries.forEach { point -> e.press(point); e.release(point) }; s4Frames(e, 2)
                    assertEquals(0, returnedToCall)
                    assertEquals(owner, assertIs<MessageUiState.Ready>(messages.state.value).ownerId)
                    assertEquals(draft, field().config[SemanticsProperties.EditableText])
                    assertEquals(cursor, field().config[SemanticsProperties.TextSelectionRange])
                    assertEquals(requests, wire.requests.toList(), "视口变化与旧入口指针不能生成业务RPC")
                    availableHeight = 844; s4Frames(e, 24)
                    val loadedBeforeHistory = assertIs<MessageUiState.Ready>(messages.state.value).records.filterIsInstance<MessageRecord.Incoming>()
                    assertTrue(loadedBeforeHistory.size > 1, "S7_IME_HISTORY：真实已加载窗口须含可浏览历史")
                    assertTrue(loadedBeforeHistory.any { it.id == ids.last() }, "S7_IME_HISTORY：当前窗口确含原最新消息")
                    val beforeHistoryAxis = list().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    historyDiagnostic("before-index1")
                    assertTrue(list().config[SemanticsActions.ScrollToIndex].action!!.invoke(1))
                    repeat(4) { e.advanceFrameBy(0) }; historyDiagnostic("index1-zero")
                    s4Frames(e, 1); historyDiagnostic("index1-first-frame")
                    s4Frames(e, 23); historyDiagnostic("index1-settled")
                    val historyAxis = list().config[SemanticsProperties.VerticalScrollAxisRange].value()
                    assertTrue(kotlin.math.abs(historyAxis - beforeHistoryAxis) > .001f,
                        "S7_IME_HISTORY：实际轴位置须改变；若回原值，先据分页/焦点/正文叶诊断判回位原因，不能将请求index当已滚动")
                    assertTrue(visibleHistoryLeaves().any { historyLabel(it) != "s7-row-79" },
                        "S7_IME_HISTORY：实际视口须呈现真实非最新正文叶")
                    assertNull(bubble("s7-row-79"), "前提：用户确已浏览历史而非仍在底部")
                    val historyLeaves = visibleHistoryLeaves()
                    assertTrue(historyLeaves.isNotEmpty(), "S7_IME_HISTORY：实际滚动后须有完整可见的真实正文叶")
                    val anchor = historyLeaves.minBy { it.boundsInRoot.top }
                    val anchorLabel = assertNotNull(historyLabel(anchor),
                        "S7_IME_HISTORY：首个完整可见正文叶须精确唯一")
                    assertEquals(1, historyLeaves.count { historyLabel(it) == anchorLabel })
                    val loadedHistoryIds = assertIs<MessageUiState.Ready>(messages.state.value).records.filterIsInstance<MessageRecord.Incoming>().map { it.id }
                    val firstVisibleRecordIndex = loadedHistoryIds.indexOf(messageIdsByLabel.getValue(anchorLabel))
                    assertTrue(firstVisibleRecordIndex in 0 until loadedHistoryIds.lastIndex,
                        "S7_IME_HISTORY：首个完整可见叶须对应真实已加载的非最新记录，不把请求index当已布局")
                    val top = anchor.boundsInRoot.top
                    availableHeight = 540; s4Frames(e, 24)
                    historyDiagnostic("history-shrink")
                    assertNull(bubble("s7-row-79"), "E1：历史位置缩高不能强拉最新")
                    val retainedLeaves = visibleHistoryLeaves().filter { historyLabel(it) == anchorLabel }
                    assertEquals(1, retainedLeaves.size, "S7_IME_HISTORY：缩高后同一真实历史正文叶仍须完整可见且唯一")
                    assertEquals(top, retainedLeaves.single().boundsInRoot.top, 2f, "真实正文叶view position保留")
                    assertEquals(top, assertNotNull(bubble(anchorLabel)).boundsInRoot.top, 2f, "真实消息锚保留")
                    assertEquals(draft, field().config[SemanticsProperties.EditableText])
                    assertEquals(cursor, field().config[SemanticsProperties.TextSelectionRange])
                    assertEquals(owner, assertIs<MessageUiState.Ready>(messages.state.value).ownerId)
                    assertEquals(requests, wire.requests.toList())
                    assertEquals(0L, store.countUnreadIncoming())
                }
            } finally {
                messages.close(); calls.source.close(); store.close(); back.navigationEventDispatcher.dispose()
            }
        }
    }
}
