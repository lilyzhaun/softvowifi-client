@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
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
import com.vowifi.sw.manager.desktop.s4InkRatio
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/* 草案：只放 /tmp；source 的 historySnapshot/await 尚待真实实现，null 是未接线，不补假快照。
 * 候选：ui/S4CallHistoryMutationRenderTest.kt；只有一项测试，不猜 CallsScreen 新参数。
 * C2 后继仅换挂起适配，不改 pending/拒绝/150ms/零动画及当前项退场恰一次守护：
 * onConfirm = { owner -> requests += owner; withTimeout(5_000) { permit.await() };
 *     backend.source.selectLine(owner); backend.source.lines.value.selectedOwner == owner }
 * permit 为每次请求独立的 CompletableDeferred<Unit>，只挂住真实 selectLine 调用。
 * 拒绝时先使 B 退役并 refresh，再放行；真实投影仍是 A，适配器返回 false。
 * 成功时保留 B 再放行；只以真实投影到 B 为确认，确认时刻起原 150ms 断言不变。
 * 历史选线捕获原 choice 引用，直接 await selectHistoryOwnerAndAwait(choice)，不从 Unit 算成功。
 */
class S4CallHistoryMutationRenderTest {
    @Test fun actualMenuDeleteKeepsPendingRowsRestoresFailureAndOnlyFadesAfterCommitAndReadback() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher(), timeout = 20.seconds) {
            for (scale in listOf(1f, 0f)) {
                val saved = FakeCallHistoryRepository()
                val target = "155501"; val following = "155502"
                listOf(target, following, "155503").forEachIndexed { index, phone ->
                    val start = T0 + (3 - index) * 10_000L
                    saved.upsert(OWNER_A, historyWrite(correlation = "corr-s4-ui-$index", peer = phone,
                        startedAtMs = start, connectedAtMs = start + 1_000, endedAtMs = start + 2_000,
                        durationSec = 1, outcome = CallOutcome.COMPLETED))
                }
                saved.upsert(OWNER_B, historyWrite(correlation = "corr-s4-ui-sibling", peer = "155504",
                    connectedAtMs = T0 + 1_000, endedAtMs = T0 + 2_000, durationSec = 1, outcome = CallOutcome.COMPLETED))
                saved.selectOwner(OWNER_A)
                val sibling = saved.listByOwner(OWNER_B)
                val entered = List(2) { CompletableDeferred<Unit>() }
                val release = List(2) { CompletableDeferred<Unit>() }
                val failed = CompletableDeferred<Unit>()
                val readbackEntered = CompletableDeferred<Unit>()
                val readbackRelease = CompletableDeferred<Unit>()
                val deletes = mutableListOf<Pair<SimOwnerId, Set<CallHistoryRecordId>>>()
                var committed = false
                val repository = object : CallHistoryRepository by saved {
                    override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                        val attempt = deletes.size
                        check(attempt in 0..1) { "原入口不得产生第三次真实删除" }
                        deletes += ownerId to ids.toSet(); entered[attempt].complete(Unit)
                        withTimeout(30_000) { release[attempt].await() }
                        if (attempt == 0) { failed.complete(Unit); throw CallHistoryStorageException() }
                        saved.deleteByIds(ownerId, ids); committed = true
                    }
                    override suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord> {
                        if (committed && ownerId == OWNER_A) {
                            readbackEntered.complete(Unit)
                            withTimeout(30_000) { readbackRelease.await() }
                        }
                        return saved.listByOwner(ownerId)
                    }
                }
                // 与已有数据合同测试相同的真实 Aggregate；无引擎/业务回调替身，也不另造 Store。
                val source = CallSourceAggregate(
                    EngineMessageRuntime({ emptyList() }, { error("本测试不允许启动引擎") }, { T0 + 120_000 }, { emptyList() }),
                    FakeCallAudioController(), CallControlTransport { _, _, _, _ -> error("本测试不允许通话请求") },
                    backgroundScope.coroutineContext, repository, { null }, { "unused" })
                val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
                try {
                    runCurrent()
                    val snapshot = assertNotNull(source.historySnapshot, "迁入前提：须先实现真实 source 原子快照，不能手填")
                    val before = assertIs<CallHistoryState.Ready>(snapshot.value.view)
                    assertSame(before, source.history.value); assertEquals(OWNER_A, before.owner)
                    assertEquals(3, before.records.size)
                    val id = assertNotNull(before.records.single { it.peer?.digits() == target }.recordId)
                    val ids = setOf(id)
                    val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
                        FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
                        FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), source)
                    val lifecycle = object : LifecycleOwner {
                        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                    }
                    val window = object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(420, 1000)
                        override val containerDpSize = DpSize(420.dp, 1000.dp)
                    }
                    var longPressMillis = 0L
                    MotionTestEnvironment(testScheduler, 420, 1000, scale) {
                        CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalWindowInfo provides window,
                            LocalNavigationEventDispatcherOwner provides back) {
                            val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
                            SideEffect { longPressMillis = timeout }
                            // 真 App -> destination -> CallsScreen；删除回调由生产入口接线，本测试从不直接调用。
                            SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.CALLS, forceDark = false)
                        }
                    }.use { e ->
                        s4Frames(e, 80)
                        fun bounds(label: String) = s3TextBounds(s4Nodes(e), label)
                        fun region(label: String): Rect {
                            val b = bounds(label)
                            // 只取自然号码字形加 1px 余量；尺寸与分数位位置无关，不截整行或正文。
                            return Rect(floor(b.left), floor(b.top), floor(b.left) + ceil(b.width) + 1,
                                floor(b.top) + ceil(b.height) + 1)
                        }
                        // §5.4 T7：原确认宿主先退场；§5.7 E2：随后才进入原仓储held，不改数据/动画守护。
                        fun confirmThroughOriginalMenu() {
                            val count = deletes.size
                            val row = s4Nodes(e, false).single { s4Matches(it, target) &&
                                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
                            // 真App同时有根HorizontalPager和内部LazyColumn，不能以全树ScrollToIndex唯一定位。
                            val list = assertNotNull(generateSequence(row.parent) { it.parent }.firstOrNull {
                                it.config.contains(SemanticsActions.ScrollToIndex) &&
                                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                            }, "命中前提：须沿当前记录行找到它自己的纵向列表")
                            val viewport = list.boundsInRoot
                            val b = row.boundsInRoot
                            assertTrue(b.width > 48 && b.height >= 64 && b.left >= viewport.left && b.right <= viewport.right &&
                                b.top >= viewport.top && b.bottom <= viewport.bottom, "命中前提：原行须完整处于真实列表内")
                            assertTrue(longPressMillis in 1L..3000L, "只能使用真实 ViewConfiguration 的有界长按时长")
                            e.press(b.center); s4Frames(e, (longPressMillis / 16).toInt() + 3)
                            assertTrue(s4HasText(e, "复制号码"), "前提：真实长按须打开原上下文菜单")
                            e.release(b.center); s4Frames(e, 40)
                            s4Tap(e, s4Action(e, "删除").boundsInRoot.center); s4Frames(e, 60)
                            assertTrue(s4HasText(e, "删除该号码的通话记录？"), "菜单原退场之后才进入原确认框")
                            assertEquals(count, deletes.size, "长按/菜单选择均不能提前发出删除")
                            fun hosts() = s4Nodes(e, false).filter { it.config.contains(SemanticsProperties.IsDialog) }
                            fun consumeNow() { repeat(6) { e.advanceFrameBy(0) } }
                            assertEquals(1, hosts().size, "前提：原删除确认宿主实际存在")
                            assertSame(before, snapshot.value.view, "确认前仍须是原Ready身份，不绕过源selection保护")
                            val point = s4Action(e, "删除").boundsInRoot.center
                            e.press(point); e.advanceFrameBy(8)
                            val releasedAt = testScheduler.currentTime
                            val dwell = (MIUIX_CONFIRMATION_EXIT_MILLIS * e.durationScale).toLong()
                            val deadline = releasedAt + dwell
                            fun diagnostic() = "scale=${e.durationScale} attempt=$count releasedAt=$releasedAt deadline=$deadline " +
                                "now=${testScheduler.currentTime} hosts=${hosts().size} started=${entered[count].isCompleted} deletes=${deletes.size}"
                            e.release(point); consumeNow()
                            assertEquals(releasedAt, testScheduler.currentTime, "释放后只泵同刻：${diagnostic()}")
                            if (dwell > 0) {
                                e.advanceFrameBy(dwell - 1); consumeNow()
                                assertEquals(1, hosts().size, "T7退场期限前原宿主不能提前卸载：${diagnostic()}")
                                assertFalse(entered[count].isCompleted, "T7退场期限前不可提前进入仓储：${diagnostic()}")
                                assertEquals(count, deletes.size, diagnostic())
                                assertSame(before, snapshot.value.view)
                            }
                            repeat((dwell / 16).toInt() + 2) {
                                consumeNow()
                                if (hosts().isNotEmpty()) {
                                    assertFalse(entered[count].isCompleted, "T7原宿主尚在，不得提前提交：${diagnostic()}")
                                    assertEquals(count, deletes.size, diagnostic())
                                    assertSame(before, snapshot.value.view, "T7退出不得换Ready：${diagnostic()}")
                                    if (testScheduler.currentTime < deadline) {
                                        e.advanceFrameBy(minOf(16L, deadline - testScheduler.currentTime)); consumeNow()
                                    }
                                }
                            }
                            consumeNow()
                            assertTrue(testScheduler.currentTime <= deadline, "不能延长官方260ms×scale：${diagnostic()}")
                            assertTrue(hosts().isEmpty(), "T7真实宿主须已卸载：${diagnostic()}")
                            if (e.durationScale == 0f) assertEquals(releasedAt, testScheduler.currentTime, diagnostic())
                            assertTrue(entered[count].isCompleted, "确认必须真正进入 repository，未命中不能算行为 RED；${diagnostic()}")
                            assertEquals(count + 1, deletes.size)
                            assertEquals(OWNER_A to ids, deletes.last(), "确认须保留源 Ready 所属线路和原稳定 id")
                        }
                        assertTrue(s4HasText(e, "通话记录")); assertSame(before, snapshot.value.view)
                        val resting = s3SmallPatch(e, testScheduler, region(target))
                        val followingTop = bounds(following).top
                        confirmThroughOriginalMenu(); s4Frames(e, 50)
                        assertSame(before, snapshot.value.view, "pending 必须保留同一个 Ready，而非乐观删行")
                        assertSame(before, source.history.value); assertEquals(ids, snapshot.value.deletePending)
                        assertEquals(before.records, saved.listByOwner(OWNER_A))
                        assertEquals(followingTop, bounds(following).top, .5f, "后端仍 held 时不能合拢")
                        assertEquals(MotionTokens.AlphaPending.toDouble(),
                            s4InkRatio(s3SmallPatch(e, testScheduler, region(target)), resting), .06,
                            "E2：真实号码字形的可见墨迹须为 0.5 alpha，不读生产动画变量")
                        assertTrue(s4Nodes(e, false).none { n -> s4Matches(n, target) &&
                            (n.config.contains(SemanticsActions.OnClick) || n.config.contains(SemanticsActions.OnLongClick)) &&
                            generateSequence(n) { it.parent }.none { it.config.contains(SemanticsProperties.Disabled) } },
                            "pending 行不得留下可执行的点击/长按动作语义")
                        val heldPoint = bounds(target).center
                        s4Tap(e, heldPoint); e.press(heldPoint); s4Frames(e, (longPressMillis / 16).toInt() + 3)
                        e.release(heldPoint); s4Frames(e, 40)
                        assertTrue(s4HasText(e, "通话记录"), "pending 真实点击不能进入详情")
                        assertFalse(s4HasText(e, "复制号码"), "pending 真实长按不能再开菜单")
                        assertFalse(s4HasText(e, "删除该号码的通话记录？")); assertEquals(1, deletes.size)
                        assertSame(before, snapshot.value.view); assertEquals(ids, snapshot.value.deletePending)

                        release[0].complete(Unit); e.advanceFrameBy(0)
                        assertTrue(failed.isCompleted, "必须真实抛出存储失败，不由测试回调假报失败")
                        repeat(40) {
                            e.advanceFrameBy(); assertSame(before, snapshot.value.view)
                            assertTrue(s3SmallPatch(e, testScheduler, region(target)).pixels.distinct().size >= 4,
                                "失败恢复期间原号码须持续实际绘制，不能消失再弹回")
                        }
                         assertTrue(snapshot.value.deletePending.isEmpty()); assertSame(before, source.history.value)
                         assertEquals(before.records, saved.listByOwner(OWNER_A))
                         assertTrue(s4HasText(e, "删除失败，请重试。"), "E2：失败须在保留原记录的同时就地说明，不能静默吞掉")
                        val restored = s3SmallPatch(e, testScheduler, region(target))
                        assertEquals(1.0, s4InkRatio(restored, resting), .06, "真实失败后原行恢复正常墨迹")
                        confirmThroughOriginalMenu(); s4Frames(e, 50)
                        assertSame(before, snapshot.value.view); assertEquals(ids, snapshot.value.deletePending)
                        val departingBounds = bounds(target)
                        val fadeRegion = region(target)
                        val pending = s3SmallPatch(e, testScheduler, fadeRegion)
                        assertEquals(MotionTokens.AlphaPending.toDouble(), s4InkRatio(pending, restored), .06)
                        val nextTop = bounds(following).top
                        assertTrue(nextTop - departingBounds.top >= 64f, "补位几何前提：离开项原高度可测")
                        release[1].complete(Unit); e.advanceFrameBy(0)
                        assertTrue(committed && readbackEntered.isCompleted, "必须真实写入且进入 Store 读回")
                        assertEquals(before.records.filterNot { it.recordId == id }, saved.listByOwner(OWNER_A))
                        s4Frames(e, 12)
                        assertSame(before, snapshot.value.view, "写入完成但读回 held 仍不能乐观消失")
                        assertEquals(ids, snapshot.value.deletePending)
                        assertEquals(nextTop, bounds(following).top, .5f)
                        assertEquals(1.0, s4InkRatio(s3SmallPatch(e, testScheduler, fadeRegion), pending), .06)

                        val readbackAt = testScheduler.currentTime
                        readbackRelease.complete(Unit); e.advanceFrameBy(0)
                        val after = assertIs<CallHistoryState.Ready>(snapshot.value.view)
                        assertSame(after, source.history.value); assertEquals(saved.listByOwner(OWNER_A), after.records)
                        assertTrue(after.records.none { it.recordId == id }); assertTrue(snapshot.value.deletePending.isEmpty())
                        val change = assertNotNull(snapshot.value.change)
                        assertEquals(HistoryChangeReason.USER_DELETE, change.reason); assertEquals(ids, change.ids)
                        if (scale == 0f) {
                            s4Frames(e, 2)
                            assertFalse(s4HasText(e, target)); assertEquals(departingBounds.top, bounds(following).top, .5f)
                            val instant = s3SmallPatch(e, testScheduler, region(following))
                            s4Frames(e, 50)
                            assertEquals(0, s4Difference(instant, s3SmallPatch(e, testScheduler, region(following))),
                                "零动画直接到同一终态，小区域不能留旧行重影")
                        } else {
                            // 每帧仅保留时间/小字形墨迹比/相邻自然文字 top，不存整行、图片或正文。
                            val samples = List(56) {
                                e.advanceFrameBy()
                                Triple(testScheduler.currentTime - readbackAt,
                                    s4InkRatio(s3SmallPatch(e, testScheduler, fadeRegion), pending), bounds(following).top)
                            }
                            val firstShift = samples.indexOfFirst { it.third < nextTop - .5f }
                            assertTrue(firstShift > 0, "成功读回后先保留原槽淡出，不能立即移除")
                            assertTrue(samples.filter { it.first < MotionTokens.DurFast }.all { abs(it.third - nextTop) <= .5f },
                                "DurFast 淡出期间相邻原行必须保持原位，不能同时提前合拢")
                            assertTrue(samples.take(firstShift).any { it.second in .03.. .97 }, "必须绘出真实淡出中间帧")
                            assertTrue(samples[firstShift - 1].second <= .04, "离开项实际淡完后才能开始合拢")
                            val end = samples.last().third
                            assertEquals(departingBounds.top, end, .5f, "末态由相邻行补到原槽")
                            assertTrue(samples.any { it.third > end + .5f && it.third < nextTop - .5f },
                                "SpringSpatial 合拢必须有自然文字位置中间帧，不能直接跳终值")
                            assertFalse(s4HasText(e, target))
                        }
                        assertEquals(2, deletes.size); assertEquals(sibling, saved.listByOwner(OWNER_B))
                        assertEquals(saved.listByOwner(OWNER_A), assertIs<CallHistoryState.Ready>(snapshot.value.view).records)
                    }
                } finally {
                    release.forEach { it.complete(Unit) }; readbackRelease.complete(Unit); runCurrent()
                    source.close(); runCurrent(); saved.close(); back.navigationEventDispatcher.dispose()
                }
            }
        }
    }

    @Test fun realHeldDeleteHasBusyDescriptionAndItsFailureIsPoliteWithoutCancellationReplay() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher(), timeout = 20.seconds) {
            val saved = FakeCallHistoryRepository(); val target = "155501"
            listOf(target, "155502").forEachIndexed { index, peer -> saved.upsert(OWNER_A,
                historyWrite(correlation = "s7-pending-$index", peer = peer, startedAtMs = T0 + index * 10_000,
                    connectedAtMs = T0 + index * 10_000 + 1_000, endedAtMs = T0 + index * 10_000 + 2_000, durationSec = 1, outcome = CallOutcome.COMPLETED)) }
            saved.selectOwner(OWNER_A)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val failed = CompletableDeferred<Unit>()
            var deletes = 0; var deleteEnteredAt: Long? = null
            val repository = object : CallHistoryRepository by saved {
                override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                    assertEquals(OWNER_A, ownerId); assertEquals(1, ids.size); deletes++; check(deletes == 1)
                    deleteEnteredAt = testScheduler.currentTime
                    entered.complete(Unit); withTimeout(30_000) { release.await() }
                    failed.complete(Unit); throw CallHistoryStorageException()
                }
            }
            val source = CallSourceAggregate(
                EngineMessageRuntime({ emptyList() }, { error("不允许启动引擎") }, { T0 + 120_000 }, { emptyList() }),
                FakeCallAudioController(), CallControlTransport { _, _, _, _ -> error("不允许通话请求") },
                backgroundScope.coroutineContext, repository, { null }, { "unused" })
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            val lifecycle = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED } }
            val window = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize = IntSize(420, 1000)
                override val containerDpSize = DpSize(420.dp, 1000.dp)
            }
            val sources = SoftVoWiFiSources(FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
                FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"), FixtureEnvironmentDiagnosticsSource("empty"),
                FixtureOperationalLogSource("empty"), FixtureMessageSource("empty"), source)
            var longPress = 0L
            try { MotionTestEnvironment(testScheduler, 420, 1000) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalWindowInfo provides window,
                    LocalNavigationEventDispatcherOwner provides back) {
                    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis; SideEffect { longPress = timeout }
                    SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.CALLS, forceDark = false)
                }
            }.use { e ->
                s4Frames(e, 80)
                val snapshot = assertNotNull(source.historySnapshot); val before = assertIs<CallHistoryState.Ready>(snapshot.value.view)
                val id = assertNotNull(before.records.single { it.peer?.digits() == target }.recordId)
                fun openOriginalConfirmation() {
                    assertTrue(s4Nodes(e, false).any { it.config.getOrNull(SemanticsProperties.Selected) == true && s4Matches(it, "通话") },
                        "前提：真实App当前页必须为通话，不能长按covered根")
                    fun targetRow() = s4Nodes(e, false).single { s4Matches(it, target) &&
                        it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
                    val initial = targetRow()
                    val list = assertNotNull(generateSequence(initial.parent) { it.parent }.firstOrNull {
                        it.config.contains(SemanticsActions.ScrollToIndex) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                    }, "前提：真实行必须属于当前纵向历史列表")
                    val viewport = list.boundsInRoot
                    val rawTop = initial.positionInRoot.y; val rawBottom = rawTop + initial.size.height
                    if (rawBottom > viewport.bottom || rawTop < viewport.top) {
                        val delta = if (rawBottom > viewport.bottom) rawBottom - viewport.bottom + 16f else rawTop - viewport.top - 16f
                        assertTrue(assertNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, delta)); s4Frames(e, 40)
                    }
                    val row = targetRow(); val b = row.boundsInRoot
                    assertFalse(row.config.contains(SemanticsProperties.Disabled))
                    assertTrue(b.width > 48 && b.height >= 64 && row.positionInRoot.y >= viewport.top &&
                        row.positionInRoot.y + row.size.height <= viewport.bottom && b.left >= viewport.left && b.right <= viewport.right,
                        "命中前提：原行完整矩形必须位于实际纵向viewport内")
                    assertTrue(longPress in 1L..3000L)
                    val point = b.center
                    e.press(point); s4Frames(e, (longPress / 16).toInt() + 3)
                    assertTrue(s4HasText(e, "复制号码")); e.release(point); s4Frames(e, 40)
                    s4Tap(e, s4Action(e, "删除").boundsInRoot.center); s4Frames(e, 60)
                    assertTrue(s4HasText(e, "删除该号码的通话记录？"))
                }
                val failureText = "删除失败，请重试。"
                openOriginalConfirmation(); s4Tap(e, s4Action(e, "取消").boundsInRoot.center)
                e.advanceFrameBy(MotionTokens.DurFast.toLong()); repeat(4) { e.advanceFrameBy(0) }
                assertEquals(0, deletes); assertFalse(entered.isCompleted); assertFalse(s4HasText(e, failureText))
                assertSame(before, snapshot.value.view)
                s4Frames(e, 40); assertFalse(s4HasText(e, "删除该号码的通话记录？"))
                assertEquals(0, deletes); assertFalse(entered.isCompleted); assertSame(before, snapshot.value.view)
                fun originalDialog() = s4Nodes(e, false).singleOrNull {
                    it.config.contains(SemanticsProperties.IsDialog) && s4Matches(it, "删除该号码的通话记录？")
                }
                openOriginalConfirmation()
                val openDialogBounds = assertNotNull(originalDialog(), "前提：确认前原T7宿主须已稳定").boundsInRoot
                s4Tap(e, s4Action(e, "删除").boundsInRoot.center)
                val releaseAt = testScheduler.currentTime
                fun consumeExitFrameNow() {
                    val at = testScheduler.currentTime
                    var previous: Pair<List<Pair<Int, Rect>>, Int>? = null
                    var stable = false
                    // 与原T7守护相同：消化真实Snapshot/帧/宿主完成回调，不推进动画或业务时间。
                    repeat(6) {
                        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                        testScheduler.runCurrent(); e.advanceFrameBy(0)
                        val current = s4Nodes(e, false).map { it.id to it.boundsInRoot } to deletes
                        stable = current == previous; previous = current
                    }
                    assertEquals(at, testScheduler.currentTime, "同刻首退出帧泵不得延长T7 deadline")
                    assertTrue(stable, "T7同刻真实布局/回调未稳定；不能靠16ms或额外等待校准")
                }
                consumeExitFrameNow()
                val firstExitFrameAt = testScheduler.currentTime
                assertEquals(releaseAt, firstExitFrameAt, "只消化同刻首退出帧，不能挪动指针释放起点")
                assertEquals(openDialogBounds, assertNotNull(originalDialog(), "前提：原T7宿主已接退出但尚未卸载").boundsInRoot,
                    "同刻首退出帧须仍是原宿主初值，不能提前采到已推进的动画")
                assertTrue(s4Actions(e, "取消").none { !it.config.contains(SemanticsProperties.Disabled) },
                    "前提：原确认框动作已即时撤权，不能把未接受的tap当退场开始")
                assertEquals(0, deletes, "T7退场完成前不得把确认当作IO已开始")
                assertFalse(entered.isCompleted)
                println("S7_T7_EXIT|releaseAt=$releaseAt|firstExitFrameAt=$firstExitFrameAt|duration=$MIUIX_CONFIRMATION_EXIT_MILLIS")
                e.advanceFrameBy(MIUIX_CONFIRMATION_EXIT_MILLIS - 1); consumeExitFrameNow()
                assertEquals(firstExitFrameAt + MIUIX_CONFIRMATION_EXIT_MILLIS - 1, testScheduler.currentTime)
                assertNotNull(originalDialog(), "T7期限前1ms原宿主仍须在，不能提前释放")
                assertEquals(0, deletes, "官方退场259ms真实repository准入必须仍为0")
                assertFalse(entered.isCompleted)
                e.advanceFrameBy(1); consumeExitFrameNow(); testScheduler.runCurrent()
                assertEquals(firstExitFrameAt + MIUIX_CONFIRMATION_EXIT_MILLIS, testScheduler.currentTime)
                assertNull(originalDialog(), "官方260ms须由原宿主完成卸载后交接")
                assertTrue(entered.isCompleted, "前提：原宿主完成退场后必须真正到达held repository")
                assertEquals(testScheduler.currentTime, deleteEnteredAt, "真实repository准入须在官方260ms，不追加await掩盖时钟")
                assertEquals(1, deletes); assertEquals(setOf(id), snapshot.value.deletePending)
                assertSame(before, snapshot.value.view); assertEquals(before.records, saved.listByOwner(OWNER_A))
                val label = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == target }
                val rowScope = generateSequence(label) { it.parent }.takeWhile { !it.config.contains(SemanticsActions.ScrollToIndex) }.toList()
                val description = rowScope.mapNotNull { it.config.getOrNull(SemanticsProperties.StateDescription) }
                val hasBusyText = description.any { "正在删除" in it || "删除中" in it }
                val failures = mutableListOf<String>()
                if (!hasBusyText) failures += "真实目标行held时必须提供正在删除的StateDescription，disabled/AlphaPending不等价"
                release.complete(Unit); e.advanceFrameBy(0); assertTrue(failed.isCompleted, "前提：仓库真实抛出失败")
                s4Frames(e, 40); assertSame(before, snapshot.value.view); assertTrue(snapshot.value.deletePending.isEmpty())
                assertEquals(before.records, saved.listByOwner(OWNER_A)); assertTrue(s4HasText(e, target))
                fun notice() = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == failureText }
                val error = notice()
                val polite = generateSequence(error) { it.parent }.takeWhile { !it.config.contains(SemanticsActions.ScrollToIndex) }
                    .any { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }
                if (!polite) failures += "原失败说明必须LiveRegion=Polite，不能仅绘字或重放全局结果"
                val noticeId = error.id; s4Frames(e, 24)
                assertEquals(noticeId, notice().id, "无新事件时失败说明不能卸载重建为再次播报")
                assertEquals(1, deletes); assertSame(before, source.history.value)
                assertTrue(failures.isEmpty(), failures.joinToString("\n"))
            } } finally { release.complete(Unit); runCurrent(); source.close(); runCurrent(); saved.close(); back.navigationEventDispatcher.dispose() }
        }
    }
}
