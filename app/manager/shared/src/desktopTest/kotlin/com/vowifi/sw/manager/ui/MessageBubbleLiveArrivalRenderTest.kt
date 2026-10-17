@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_B
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MO_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sourceOf
import com.vowifi.sw.manager.desktop.s4HasLabel
import com.vowifi.sw.manager.desktop.s4InkRatio
import com.vowifi.sw.manager.desktop.s4List
import com.vowifi.sw.manager.desktop.s4Nodes as threadNodes
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.*

/** B1：真实source→timeline→LazyColumn自动跟随；不孤立气泡、不预写enter/动画终值。 */
class MessageBubbleLiveArrivalRenderTest {
    @Test fun fastAcceptedMoBeforeAnyRenderStillHasARealSurfaceEntrance() = onMotionTestThread { runTest {
        Fixture.create(this, count = 3).use { f ->
            val body = "B1快速受理合成MO"
            val old = f.sample("B1历史2", outgoing = false)
            val previous = f.ready()
            val ids = previous.records.map { it.id }.toSet()
            val timeBeforeSend = f.scope.testScheduler.currentTime
            assertTrue(f.transportGate.complete(Unit), "前提：发送前已开放真实合成RPC响应，不等待UI帧")
            f.source.sendFromDisplayedOwner(f.identity.owner, SYNTH_NUMBER, body)
            f.scope.testScheduler.runCurrent()
            val accepted = f.ready()
            val added = accepted.records.filterIsInstance<MessageRecord.Outgoing>().single { it.id !in ids }
            assertEquals(body, added.preview); assertEquals(OutgoingMessageStatus.ACCEPTED, added.status)
            assertEquals(MO_ID, added.engineSmsId)
            assertEquals(ids + added.id, accepted.records.map { it.id }.toSet())
            assertEquals(1, f.repository.insertQueuedCalls); assertEquals(1, f.repository.bindAcceptedCalls)
            assertEquals(listOf("sendSms"), f.transport.requests.map { it.method })
            val change = assertNotNull(accepted.window?.change)
            assertEquals(HistoryChangeReason.CONTENT, change.reason,
                "前提：queue与真实快受理已在首次UI消费前合并为CONTENT，不手造LIVE资格")
            assertTrue(change.scope === assertNotNull(previous.window?.change).scope)
            assertTrue(change.version > assertNotNull(previous.window?.change).version)
            assertTrue(added.id in change.liveInsertedIds,
                "真实选中窗口必须保留已持久入场依据，不能被首个LIVE读的后继revision取消")
            assertEquals(timeBeforeSend, f.scope.testScheduler.currentTime, "前提：尚未推进任何新UI帧")
            val samples = f.timeline(body, outgoing = true)
            f.frames(60)
            f.assertArrival(samples, f.sample(body, outgoing = true), outgoing = true)
            f.assertOldPlacement(old, samples, f.sample("B1历史2", false))
        }
    } }

    @Test fun queuedMoFirstVisibleSurfaceAndOldRowsShareAContinuousArrival() = onMotionTestThread { runTest {
        for (wrapped in listOf(false, true)) Fixture.create(this, count = 3).use { f ->
            val body = if (wrapped) "B1合成多行新MO，检查正文与气泡整体入场。".repeat(3) else "B1合成新MO"
            val old = f.sample("B1历史2", outgoing = false)
            f.insert(body, outgoing = true)
            val samples = f.timeline(body, outgoing = true)
            f.frames(60)
            val terminal = f.sample(body, outgoing = true)
            if (wrapped) assertTrue(terminal.lines >= 2, "前提：MO实际排版已换行")
            f.assertArrival(samples, terminal, outgoing = true)
            f.assertOldPlacement(old, samples, f.sample("B1历史2", false))
            if (!wrapped) f.assertContentPress(body, terminal)
            assertEquals(1, f.repository.insertQueuedCalls, "真实queue只落库一次")
            assertEquals(listOf("sendSms"), f.transport.requests.map { it.method })
        }
    } }

    @Test fun wrappedMtBelowTheViewportMustNotBecomeOpaqueOnlyAfterAutoFollow() = onMotionTestThread { runTest {
        for (wrapped in listOf(false, true)) Fixture.create(this, count = 20).use { f ->
            val old = f.sample("B1历史19", false)
            val viewport = s4List(f.environment).boundsInRoot
            assertTrue(old.bubble.bottom > viewport.bottom - 100f, "前提：真实最新消息在底部附近")
            val body = if (wrapped) "B1合成多行新MT，检查整段正文随气泡进入而不是裁剪。".repeat(3) else "B1合成新MT"
            f.insert(body, outgoing = false)
            val samples = f.timeline(body, false)
            f.frames(60)
            val terminal = f.sample(body, false)
            if (wrapped) assertTrue(terminal.lines >= 2, "前提：MT实际Text排版确已换行")
            f.assertArrival(samples, terminal, outgoing = false)
            f.assertOldPlacement(old, samples, f.sample("B1历史19", false))
            assertTrue(f.transport.requests.isEmpty(), "只模拟Received，不得发送RPC")
            if (!wrapped) {
                val beforeScroll = f.ready()
                assertTrue(s4List(f.environment).config[SemanticsActions.ScrollToIndex].action!!.invoke(0))
                f.frames(24)
                assertFalse(threadNodes(f.environment).any { s4HasLabel(it, body) && it.boundsInRoot.height > 0f },
                    "前提：已实际滚离并释放新消息的可见项，不能只重组仍在屏内的气泡")
                val thread = assertNotNull(projectMessageThread(f.identity.handle, beforeScroll.ownerId, beforeScroll, ::messageDateSpec))
                assertTrue(s4List(f.environment).config[SemanticsActions.ScrollToIndex].action!!.invoke(thread.items.size))
                f.consume()
                assertEquals(beforeScroll.window?.change, f.ready().window?.change, "前提：滚回没有新版本或源事件")
                assertEquals(1f, f.sample(body, false).alpha, .05f,
                    "同一实时版本的历史气泡滚回首可见帧必须全显，不能再次入场")
            }
        }
    } }

    @Test fun immediateContentAndTwoMoreLiveIdsCannotResetAnExistingEntrance() = onMotionTestThread { runTest {
        Fixture.create(this, count = 3).use { f ->
            val body = "B1连续MO"
            f.insert(body, true); f.environment.advanceFrameBy(16)
            val before = f.sample(body, true)
            f.transportGate.complete(Unit); f.scope.testScheduler.runCurrent(); f.consume()
            assertEquals(HistoryChangeReason.CONTENT, f.ready().window?.change?.reason,
                "前提：真实发送受理产生同id内容更新，不手造CONTENT")
            val content = f.sample(body, true)
            assertTrue(content.alpha >= before.alpha - .04f && content.text.width >= before.text.width - .5f,
                "CONTENT不能把当前气泡透明度/正文缩放重置或撤销")
            f.insert("B1紧接MT一", false); f.environment.advanceFrameBy(16)
            val first = f.sample(body, true)
            f.insert("B1紧接MT二", false); f.environment.advanceFrameBy(16)
            val second = f.sample(body, true)
            assertTrue(second.alpha >= first.alpha - .04f && second.text.width >= first.text.width - .5f,
                "后继LIVE_INSERT不得重播先前稳定id的入场")
            f.frames(60)
            assertEquals(1, f.repository.insertQueuedCalls)
            assertEquals(1, f.ready().records.filterIsInstance<MessageRecord.Outgoing>().size)
        }
    } }

    @Test fun zeroMotionAndHistoryReplacePageMergeAndOwnerSwitchDoNotReplayLiveEntrance() = onMotionTestThread { runTest {
        Fixture.create(this, count = 61, scale = 0f).use { f ->
            f.insert("B1零动效MT", false)
            val zero = f.sample("B1零动效MT", false)
            f.frames(12); val end = f.sample("B1零动效MT", false)
            assertEquals(1f, zero.alpha, .05f); assertEquals(end.bubble, zero.bubble)
            f.source.loadOlderMessages(); f.scope.testScheduler.runCurrent()
            assertEquals(HistoryChangeReason.PAGE_MERGE, f.ready().window?.change?.reason)
            f.consume(); assertEquals(1f, f.sample("B1零动效MT", false).alpha, .05f)
            f.source.selectConversation(null); f.scope.testScheduler.runCurrent()
            f.source.selectConversation(f.identity.handle.value); f.scope.testScheduler.runCurrent()
            assertEquals(HistoryChangeReason.REPLACE, f.ready().window?.change?.reason)
            f.consume(); assertEquals(1f, f.sample("B1零动效MT", false).alpha, .05f)
            val other = StoredMessageConversation(assertNotNull(ownerFor(ICCID_B)), SYNTH_NUMBER.drop(1))
            f.source.selectHistoryOwner(other.owner)
            f.source.selectConversation(other.handle.value); f.scope.testScheduler.runCurrent(); f.handle = other.handle
            f.consume(); f.frames(4)
            assertEquals(other.owner, f.ready().ownerId, "前提：真实history owner已切换")
            assertEquals(HistoryChangeReason.REPLACE, f.ready().window?.change?.reason)
            assertEquals(1f, f.sample("B1另一线路历史", false).alpha, .05f)
        }
    } }

    private data class Sample(val ms: Long, val bubble: Rect, val text: Rect, val alpha: Float,
        val lines: Int, val oldTop: Float?)

    private class Fixture(val scope: TestScope, val repository: FakeMessageRepository,
        val transport: FakeEngineMessageTransport, val source: EngineMessageSource,
        val identity: StoredMessageConversation, val transportGate: CompletableDeferred<Unit>, scale: Float, val count: Int) : AutoCloseable {
        var handle by mutableStateOf(identity.handle)
        private var canvas = 0; private var incoming = 0; private var outgoing = 0
        private var serial = 0
        val environment = MotionTestEnvironment(scope.testScheduler, 420, 900, durationScale = scale) {
            s4SmsHost {
                // ThreadScreen内部同样使用PhoneStyle；从相同生产provider读取，不在父层读fallback palette。
                PhoneStyle {
                val colors = communicationColors()
                SideEffect { canvas = colors.timeline.toArgb(); incoming = colors.field.toArgb(); outgoing = colors.outgoing.toArgb() }
                val state by source.state.collectAsState()
                MessageThreadScreen(handle, state, { number, body -> source.sendFromDisplayedOwner(
                    (state as? MessageUiState.Available)?.ownerId, number, body) }, onLoadOlder = source::loadOlderMessages)
                }
            }
        }
        fun ready() = assertIs<MessageUiState.Ready>(source.state.value)
        fun frames(n: Int) { repeat(n) { environment.advanceFrameBy(16) } }
        fun assertContentPress(body: String, rest: Sample) {
            val region = Rect(rest.text.left, rest.text.top, minOf(rest.text.right, rest.text.left + 64f), rest.text.bottom)
            val before = s3SmallPatch(environment, scope.testScheduler, region)
            environment.press(rest.bubble.center)
            try {
                frames(20)
                val held = sample(body, true)
                assertEquals(rest.bubble, held.bubble, "P5不能缩放气泡表面或点击区域")
                val ink = s3SmallPatch(environment, scope.testScheduler, region)
                assertEquals(MotionTokens.AlphaPressDim.toDouble(), s4InkRatio(ink, before), .06,
                    "真实气泡指针按住仍须使用原source-owned P5透明度，不能为修正文错位禁按压")
            } finally {
                environment.release(rest.bubble.center)
                frames(20)
            }
            assertEquals(1.0, s4InkRatio(s3SmallPatch(environment, scope.testScheduler, region), before), .04,
                "真实松手后须恢复原正文墨迹")
        }
        fun consume() { repeat(4) { environment.advanceFrameBy(0) } }
        suspend fun insert(body: String, outgoing: Boolean) {
            val previous = ready().records.map { it.id }.toSet()
            if (outgoing) source.sendFromDisplayedOwner(identity.owner, SYNTH_NUMBER, body)
            else transport.emitReceived("b1-live-${++serial}", SYNTH_NUMBER, body, NOW_MS / 1000 + serial)
            scope.testScheduler.runCurrent()
            val change = assertNotNull(ready().window?.change)
            assertEquals(HistoryChangeReason.LIVE_INSERT, change.reason)
            assertTrue(change.ids.any { it !in previous }, "前提：真实落库/Received形成新稳定id")
            consume()
        }
        private fun bubble(body: String): SemanticsNode? = threadNodes(environment).singleOrNull {
            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" && s4HasLabel(it, body)
        }
        fun sample(body: String, outgoing: Boolean): Sample =
            assertNotNull(sampleAboveComposer(body, outgoing), "采样前提：气泡须已露出到悬浮输入栏上方")
        /** 2026-10-17：时间线延伸到悬浮输入栏下方；升起中的新MT下部可暂在输入栏后方，只取已露出部分的像素。 */
        fun sampleAboveComposer(body: String, outgoing: Boolean): Sample? = environment.scene.render(scope.testScheduler.currentTime * 1_000_000L).use { image ->
            // 先render，再读这张image对应的语义/排版；不能在取坐标后触发另一次布局。
            val surface = assertNotNull(bubble(body), "采样前提：真实消息表面语义必须存在").boundsInRoot
            val viewport = s4List(environment).boundsInRoot
            val composerTop = threadNodes(environment).single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true &&
                    it.config.contains(SemanticsProperties.EditableText)
            }.boundsInRoot.top - 8f
            // 右侧内边/圆角内、输入栏顶部透明带之上的最高纯表面行；露出不足时该帧不可采样。
            val y = floor(minOf(surface.center.y - 1f, composerTop - 4f)).toInt()
            if (y < surface.top + 6f) return@use null
            assertTrue(surface.width >= 16f && surface.height >= 20f && surface.top >= viewport.top &&
                surface.left >= viewport.left && surface.right <= viewport.right,
                "采样前提：气泡已实际露出，不以滚入裁剪伪装alpha/scale；surface=$surface viewport=$viewport")
            fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
            val text = environment.scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }.single {
                it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.let { t -> stripMessageDisplayJoiners(t.text) == body } == true &&
                    it.config.contains(SemanticsActions.GetTextLayoutResult) && !it.config.contains(SemanticsActions.SetText)
            }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(text.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
            val layout = layouts.single(); assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized))
            fun assertVisibleBody(alpha: Float) = assertTrue(text.boundsInRoot.left >= surface.left - 1f && text.boundsInRoot.right <= surface.right + 1f &&
                text.boundsInRoot.top >= surface.top - 1f && text.boundsInRoot.bottom <= surface.bottom + 1f,
                "正文必须随真实气泡整体进入，不能绘制到表面外；alpha=$alpha,surface=$surface,body=${text.boundsInRoot}," +
                    "chain=" + generateSequence(text.layoutInfo) { it.parentInfo }.take(5).map { info ->
                        val coordinate = info.coordinates
                        val root = generateSequence(coordinate) { it.parentCoordinates }.last()
                        "${info.semanticsId}:${root.localBoundingBoxOf(coordinate, clipBounds = false)}"
                    }.toList() + ";coordinators=" + generateSequence(text.layoutInfo.coordinates) {
                        it.parentCoordinates
                    }.take(12).map { coordinates ->
                        "${coordinates::class.simpleName}:${coordinates.size}:" +
                            coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero) + ":" +
                            (coordinates.parentCoordinates?.localPositionOf(coordinates, androidx.compose.ui.geometry.Offset.Zero))
                    }.toList())
            // 右侧内边避开正文/圆角，也避开尚在让位的较窄旧MT表面；不能把旧底色当新消息alpha。
            val x = floor(surface.right - 7f).toInt()
            val patchBounds = Rect(x.toFloat(), y.toFloat(), x + 3f, y + 3f)
            assertFalse(threadNodes(environment).any { other ->
                other.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" &&
                    !s4HasLabel(other, body) && other.boundsInRoot.overlaps(patchBounds)
            }, "像素前提：采样区不能被其它消息表面占据，同色叠绘不是新气泡alpha")
            val (pixels, background) = Bitmap.makeFromImage(image).use { bitmap ->
                fun patch(left: Int) = (0..2).flatMap { dy -> (0..2).map { dx -> bitmap.getColor(left + dx, y + dy) } }
                patch(x) to patch(floor(viewport.left + 2f).toInt()) // timeline左内距16dp内的空白，不用终态气泡归一化。
            }
            val color = if (outgoing) this.outgoing else incoming
            assertTrue(listOf(16, 8, 0).all { shift -> background.all {
                abs((it ushr shift and 255) - (canvas ushr shift and 255)) <= 2
            } }, "像素前提：同帧邻近真实背景须匹配PhoneStyle内部timeline，不能以错误父palette校准")
            assertTrue(listOf(16, 8, 0).all { shift -> pixels.maxOf { it ushr shift and 255 } -
                pixels.minOf { it ushr shift and 255 } <= 2 }, "像素前提：3×3必须纯表面，不拿字形/边框作alpha")
            val actualCanvas = background.first()
            val deltas = listOf(16, 8, 0).map { ((color ushr it) and 255) - ((actualCanvas ushr it) and 255) }
            val norm = deltas.sumOf { it * it }.toFloat(); assertTrue(norm > 16f, "像素前提：气泡与timeline须可区分")
            val alpha = pixels.map { pixel -> listOf(16, 8, 0).mapIndexed { i, shift ->
                (((pixel ushr shift) and 255) - ((actualCanvas ushr shift) and 255)) * deltas[i]
            }.sum() / norm }.average().toFloat()
            // 与首个实际可见帧共用阈值；alpha=0的预放置不冒充用户已经看见的正文。
            if (alpha > .02f) assertVisibleBody(alpha)
            Sample(scope.testScheduler.currentTime, surface, text.boundsInRoot, alpha, layout.lineCount,
                bubble("B1历史${count - 1}")?.boundsInRoot?.top)
        }
        fun timeline(body: String, outgoing: Boolean): List<Sample> {
            val values = mutableListOf<Sample>(); var previous = 0
            for (ms in listOf(0, 16, 32, 64, 120)) {
                environment.advanceFrameBy((ms - previous).toLong()); previous = ms
                if (bubble(body) != null) sampleAboveComposer(body, outgoing)?.let { values += it.copy(ms = ms.toLong()) }
            }
            assertTrue(values.isNotEmpty(), "前提：生产自动跟随后新气泡必须完整升入真实viewport的输入栏上方")
            return values
        }
        fun assertArrival(values: List<Sample>, terminal: Sample, outgoing: Boolean) {
            assertEquals(1f, terminal.alpha, .05f, "像素前提：终态应实际绘制主题表面原色")
            val first = assertNotNull(values.firstOrNull { it.alpha > .02f }, "前提：采样窗必须含实际绘制而非仅有语义")
            println("B1_SURFACE|outgoing=$outgoing|samples=" + values.map { listOf(it.ms, it.alpha, it.bubble.top, it.text.width / terminal.text.width, it.oldTop) })
            assertTrue(first.alpha < .98f, "B1：首个实际可见表面必须处在淡入中，不得等滚入viewport时已完全不透明")
            if (outgoing) {
                val scale = first.text.width / terminal.text.width
                assertTrue(scale >= MotionTokens.ScaleEnterFrom - .005f && scale < 1f, "B1：真实首可见MO正文应随既有ScaleEnterFrom入场")
                assertEquals(scale, first.text.height / terminal.text.height, .01f, "不是多行裁剪造成的假缩放")
            }
            assertTrue(values.any { it.alpha > first.alpha + .02f && it.alpha < 1.02f }, "后续实际表面须连续趋向终态")
        }
        fun assertOldPlacement(before: Sample, values: List<Sample>, end: Sample) {
            val distance = abs(end.bubble.top - before.bubble.top)
            assertTrue(distance > 1f, "前提：底部真实插入确让旧气泡让位")
            val first = assertNotNull(values.firstOrNull { it.alpha > .02f }?.oldTop, "前提：采到旧最新条目的同时帧")
            assertTrue(abs(first - before.bubble.top) < distance - .5f,
                "B1：LIVE_INSERT旧条目应走既有placement spring，不能首可见帧已瞬移完整新行高度")
        }
        override fun close() { transportGate.complete(Unit); environment.close(); source.close(); scope.testScheduler.runCurrent(); repository.close() }
        companion object {
            suspend fun create(scope: TestScope, count: Int, scale: Float = 1f): Fixture {
                val repository = FakeMessageRepository(); val owner = assertNotNull(ownerFor(ICCID_A))
                repeat(count) { i -> repository.insertIncomingIfAbsent(owner, "b1-old-$i", "b1-wire-$i", SYNTH_NUMBER,
                    "B1历史$i", NOW_MS - (count - i) * 1000L, NOW_MS) }
                repository.insertIncomingIfAbsent(assertNotNull(ownerFor(ICCID_B)), "b1-other", "b1-other-wire",
                    SYNTH_NUMBER, "B1另一线路历史", NOW_MS - 1000L, NOW_MS)
                val gate = CompletableDeferred<Unit>()
                val transport = FakeEngineMessageTransport(sendResult = EngineMessagePersistenceFixtures.sendOk(MO_ID, 202, 1), requestGate = gate)
                val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
                scope.testScheduler.runCurrent(); val identity = StoredMessageConversation(owner, SYNTH_NUMBER.drop(1))
                source.selectConversation(identity.handle.value); scope.testScheduler.runCurrent()
                return Fixture(scope, repository, transport, source, identity, gate, scale, count).also { f ->
                    f.frames(80)
                    assertFalse(s4List(f.environment).config[SemanticsProperties.VerticalScrollAxisRange].value() <
                        s4List(f.environment).config[SemanticsProperties.VerticalScrollAxisRange].maxValue() - .5f,
                        "前提：真实首次到底已完成，再开放live source变更")
                    f.sample("B1历史${count - 1}", false)
                }
            }
        }
    }
}
