@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MO_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sourceOf
import com.vowifi.sw.manager.desktop.s4HasLabel
import com.vowifi.sw.manager.desktop.s4List
import com.vowifi.sw.manager.desktop.s4Nodes as nodes
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.*

/**
 * 2026-10-17 user request: the composer floats over the timeline, its field width follows the send slot,
 * a composer send flies from the field into its persisted queued bubble, and a followed MT rises with the
 * rows it pushes. Real source/Room fake/transport gate, real pointer input and rendered pixels only.
 */
class MessageFloatingComposerRenderTest {
    @Test fun timelineRunsUnderTheFloatingComposerAndTheLatestBubbleRestsAboveIt() = onMotionTestThread { runTest {
        Fixture.create(this, count = 20).use { f ->
            val list = s4List(f.environment).boundsInRoot
            val capsule = f.field().boundsInRoot
            val latest = f.bubble("F历史19").boundsInRoot
            assertTrue(list.bottom >= capsule.bottom, "时间线viewport须延伸到悬浮输入栏下方；list=$list capsule=$capsule")
            assertTrue(latest.bottom <= capsule.top - 8f, "到底静止时最新消息须完整停在悬浮输入栏上方；latest=$latest capsule=$capsule")
            val band = capsule.top - 4f
            assertTrue(f.scrollBy(-(band + 16f - latest.bottom)), "真实列表须接受ScrollBy")
            f.frames(40)
            val moved = f.bubble("F历史19").boundsInRoot
            assertTrue(moved.top < band && moved.bottom > band, "前提：最新气泡已被滚到输入栏顶部透明带；moved=$moved band=$band")
            assertTrue(s3RgbDistance(f.pixel(moved.left + 6f, band), f.incomingArgb) <= 3,
                "输入栏不得整宽遮挡信息流：透明带须露出其下的真实气泡")
        }
    } }

    @Test fun sendSlotFollowsRealDraftTextAndTheFieldWidthMovesContinuously() = onMotionTestThread { runTest {
        val narrowWidth = SlotRig(testScheduler, 0f).use { f ->
            val full = f.fieldWidth()
            assertNull(f.send(), "空草稿不渲染发送槽")
            f.edit("甲"); f.consume()
            assertNotNull(f.send(), "零动画同样立即给出真实发送动作")
            assertEquals(full - 56f, f.fieldWidth(), 1f, "零动画终值：8dp间距+48dp发送目标")
            f.edit(""); f.consume()
            assertNull(f.send()); assertEquals(full, f.fieldWidth(), 1f)
            full - 56f
        }
        SlotRig(testScheduler, 1f).use { f ->
            val full = f.fieldWidth()
            assertEquals(narrowWidth + 56f, full, 1f, "前提：正常/零动画的完整宽度一致")
            f.edit("甲"); f.consume()
            val widths = List(6) { f.frames(1); f.fieldWidth() }
            println("FLOATING_SLOT|enter=$widths|full=$full|narrow=$narrowWidth")
            assertTrue(widths.first() < full - .5f && widths.first() > narrowWidth + .5f,
                "输入框宽度须随发送槽连续收窄，不能一帧跳到终值；$widths")
            assertTrue(widths.zipWithNext().all { (a, b) -> b <= a + .5f }, "收窄须单调；$widths")
            f.frames(40)
            assertEquals(narrowWidth, f.fieldWidth(), 1f)
            assertNotNull(f.send(), "有正文后出现真实发送动作")
            f.edit(""); f.consume(); f.frames(2)
            val growing = f.fieldWidth()
            assertTrue(growing > narrowWidth + .5f && growing < full - .5f, "清空后输入框须连续展开；width=$growing")
            f.frames(40)
            assertEquals(full, f.fieldWidth(), 1f)
            assertNull(f.send(), "退场结束后不保留透明发送命中层")
            assertEquals(0, f.sendCalls)
        }
    } }

    @Test fun composerSendHandsItsGlyphsToAFlightThatLandsOnTheQueuedBubble() = onMotionTestThread { runTest {
        Fixture.create(this, count = 3).use { f ->
            val body = "F合成悬浮发送"
            val pending = f.sendThroughComposer(body)
            assertEquals(body, pending.text, "前提：落库前输入框仍是被冻结的原草稿")
            val handoff = f.untilHandoff()
            val flight = assertNotNull(f.flight.flight, "交接帧必须已有飞行层")
            assertEquals(body, flight.body)
            assertEquals(0f, f.flight.progress.value, .02f, "交接帧飞行尚在原输入位置")
            assertEquals(pending.glyphs, handoff.glyphs, "交接帧被测区域须为同一矩形")
            val ink = f.inkRatio(handoff.patch, pending.patch, pending.background)
            assertTrue(ink >= .85, "原输入字形须由飞行层原位接手，不能先消失或跳位；ink=$ink")
            assertEquals(1, f.repository.insertQueuedCalls, "真实queue已落库")
            assertEquals(listOf("sendSms"), f.transport.requests.map { it.method })
            assertTrue(f.sendBusy(), "RPC未返回前仍是发送中，草稿仍保留在提交状态")
            val target = f.bubble(body).boundsInRoot
            assertTrue(s3RgbDistance(f.pixel(target.right - 4f, target.center.y), f.canvasArgb) <= 3,
                "真实持久化气泡在飞行落地前保持隐藏，不能与飞行层重影")
            var lastTop = Float.MAX_VALUE
            var surfaced = false
            var landed: Rect? = null
            for (step in 0 until 60) {
                f.frames(1)
                val current = f.flight.flight ?: break
                val frame = f.frame(current)
                assertTrue(frame.top <= lastTop + .5f, "飞行层须连续向目标气泡移动；frame=$frame last=$lastTop")
                lastTop = frame.top
                landed = frame
                if (f.flight.progress.value in .45f..1f && frame.height >= 40f) {
                    val surface = f.pixel(frame.right - 4f, frame.center.y)
                    assertTrue(s3RgbDistance(surface, f.outgoingArgb) <= 6, "飞行中须实际绘制发出气泡表面；frame=$frame")
                    surfaced = true
                }
            }
            assertNull(f.flight.flight, "飞行须在有限时间内落地")
            assertTrue(surfaced, "采样窗必须包含真实绘制的飞行气泡")
            val end = f.bubble(body).boundsInRoot
            assertNotNull(landed)
            assertEquals(end.top, landed.top, 1.5f, "落地帧与真实气泡重合")
            assertEquals(end.right, landed.right, 1.5f)
            assertTrue(s3RgbDistance(f.pixel(end.right - 4f, end.center.y), f.outgoingArgb) <= 3, "落地后由真实气泡接管绘制")
            assertEquals("", f.fieldText(), "等待结果期间正文已交给排队气泡")
            f.transportGate.complete(Unit); f.scope.testScheduler.runCurrent(); f.consume(); f.frames(40)
            assertEquals("", f.fieldText())
            assertTrue(f.hasText("已受理，不代表接收设备已收到。"), "受理说明仍按C12原文呈现")
            assertNull(f.sendAction(), "受理清空后发送槽退场")
            assertEquals(1, f.repository.insertQueuedCalls); assertEquals(1, f.repository.bindAcceptedCalls)
        }
    } }

    @Test fun failedSendReturnsTheRetainedDraftAfterItsFlight() = onMotionTestThread { runTest {
        Fixture.create(this, count = 3, failSend = true).use { f ->
            val body = "F合成失败保留"
            f.sendThroughComposer(body)
            f.untilHandoff()
            f.frames(60)
            assertNull(f.flight.flight)
            assertEquals("", f.fieldText())
            f.transportGate.complete(Unit); f.scope.testScheduler.runCurrent(); f.consume(); f.frames(40)
            assertEquals(body, f.fieldText(), "失败后原草稿回到输入框，不自动重发")
            assertTrue(f.hasText("发送未完成，草稿已保留。"))
            assertEquals(listOf("sendSms"), f.transport.requests.map { it.method })
            assertNotNull(f.sendAction(), "保留草稿后发送动作仍在")
        }
    } }

    @Test fun zeroMotionSendHandsOffWithoutAnyFlightFrame() = onMotionTestThread { runTest {
        Fixture.create(this, count = 3, scale = 0f).use { f ->
            val body = "F零动画发送"
            f.sendThroughComposer(body)
            var flights = 0
            repeat(12) { f.frames(1); if (f.flight.flight != null) flights++ }
            assertEquals(0, flights, "动画缩放为0时不产生飞行层")
            assertEquals("", f.fieldText(), "交接终值与正常动画一致")
            val end = f.bubble(body).boundsInRoot
            assertTrue(s3RgbDistance(f.pixel(end.right - 4f, end.center.y), f.outgoingArgb) <= 3, "真实气泡直接可见")
        }
    } }

    @Test fun composerSendFollowsToTheBottomEvenWhenTheReaderScrolledAway() = onMotionTestThread { runTest {
        Fixture.create(this, count = 20).use { f ->
            assertTrue(f.scrollBy(-400f)); f.frames(40)
            // Lazy axis is index-based: maxValue exceeds value exactly while the list can still scroll forward.
            assertTrue(f.axis().let { it.value() < it.maxValue() - .5f }, "前提：读者已离开底部")
            val body = "F离底发送"
            f.sendThroughComposer(body)
            f.untilHandoff()
            f.frames(60)
            assertNull(f.flight.flight)
            assertEquals(f.axis().maxValue(), f.axis().value(), .5f, "自己从输入栏发出的消息必须落在视野内")
            val end = f.bubble(body).boundsInRoot
            assertTrue(end.bottom <= f.field().boundsInRoot.top, "落地气泡停在悬浮输入栏上方")
            assertFalse(f.hasText("查看新消息"), "自己发送不显示新消息提示")
        }
    } }

    @Test fun followedMtRisesFromBehindTheComposerInLockstepWithThePushedRows() = onMotionTestThread { runTest {
        Fixture.create(this, count = 20).use { f ->
            val oldBefore = f.bubble("F历史19").boundsInRoot
            f.receive("F合成新MT")
            val samples = mutableListOf<Triple<Long, Float, Float>>()
            var previous = 0L
            for (ms in listOf(0L, 16L, 32L, 64L, 120L)) {
                f.environment.advanceFrameBy(ms - previous); previous = ms
                val incoming = f.bubbleOrNull("F合成新MT") ?: continue
                samples += Triple(ms, incoming.boundsInRoot.top, f.bubble("F历史19").boundsInRoot.top)
            }
            f.frames(60)
            val newEnd = f.bubble("F合成新MT").boundsInRoot
            val oldEnd = f.bubble("F历史19").boundsInRoot
            val push = oldBefore.top - oldEnd.top
            assertTrue(push > 20f, "前提：新MT确实把旧行推高；push=$push")
            assertEquals(push, newEnd.height + 12f, 1.5f, "推高距离=新行高+一个列表间距")
            println("FLOATING_MT|samples=$samples|newEnd=$newEnd|oldEnd=$oldEnd")
            val moving = samples.filter { it.second - newEnd.top > 1f }
            assertTrue(moving.isNotEmpty(), "新MT须从输入栏后方升起，不能在终位直接出现；$samples")
            moving.forEach { (ms, newTop, oldTop) ->
                assertEquals(oldTop - oldEnd.top, newTop - newEnd.top, 2.5f,
                    "新MT须与被推高的旧行同速同弹簧刚性移动；ms=$ms")
            }
            assertEquals(newEnd.top, f.bubble("F合成新MT").boundsInRoot.top, .5f)
        }
    } }

    @Test fun mtThatArrivedWhileReadingHistoryDoesNotRiseWhenScrolledIntoView() = onMotionTestThread { runTest {
        Fixture.create(this, count = 20).use { f ->
            assertTrue(f.scrollBy(-400f)); f.frames(40)
            f.receive("F离底新MT")
            f.frames(20)
            assertTrue(f.hasText("查看新消息"), "前提：读者在上方时显示新消息提示")
            assertNull(f.bubbleOrNull("F离底新MT")?.takeIf { it.boundsInRoot.height > 0f && it.boundsInRoot.top < f.field().boundsInRoot.top },
                "前提：新MT尚未进入可见区")
            f.tap("查看新消息")
            var checked = false
            for (step in 0 until 80) {
                f.frames(1)
                val incoming = f.bubbleOrNull("F离底新MT")?.boundsInRoot ?: continue
                val previous = f.bubbleOrNull("F历史19")?.boundsInRoot ?: continue
                if (incoming.height <= 0f) continue
                assertEquals(previous.bottom + 12f, incoming.top, 1.5f, "历史中的新消息滚入时不再重播升起位移")
                checked = true
                break
            }
            assertTrue(checked, "前提：点按提示后新MT真实滚入")
        }
    } }

    private class Snapshot(val text: String, val glyphs: Rect, val patch: S3SmallPatch, val background: Int)

    private class Fixture(val scope: TestScope, val repository: FakeMessageRepository,
        val transport: FakeEngineMessageTransport, val source: EngineMessageSource,
        val identity: StoredMessageConversation, val transportGate: CompletableDeferred<Unit>, scale: Float) : AutoCloseable {
        val flight = MessageSendFlightState()
        var canvasArgb = 0; var incomingArgb = 0; var outgoingArgb = 0
        private var density = 1f
        private var serial = 0
        val environment = MotionTestEnvironment(scope.testScheduler, 420, 900, durationScale = scale) {
            s4SmsHost {
                PhoneStyle {
                    val colors = communicationColors()
                    val localDensity = LocalDensity.current.density
                    SideEffect {
                        canvasArgb = colors.timeline.toArgb(); incomingArgb = colors.field.toArgb()
                        outgoingArgb = colors.outgoing.toArgb(); density = localDensity
                    }
                    val state by source.state.collectAsState()
                    MessageThreadScreen(identity.handle, state, { number, body -> source.sendFromDisplayedOwner(
                        (state as? MessageUiState.Available)?.ownerId, number, body) }, onLoadOlder = source::loadOlderMessages,
                        sendFlight = flight)
                }
            }
        }
        fun ready() = assertIs<MessageUiState.Ready>(source.state.value)
        fun sendBusy() = ready().sendBusy
        fun frames(n: Int) { repeat(n) { environment.advanceFrameBy(16) } }
        fun consume() { repeat(4) { environment.advanceFrameBy(0) } }
        fun field(): SemanticsNode = nodes(environment).single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true &&
                it.config.contains(SemanticsProperties.EditableText)
        }
        fun fieldText() = field().config[SemanticsProperties.EditableText].text
        fun sendAction(): SemanticsNode? = nodes(environment).singleOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.let { d -> "发送短信" in d || "发送中" in d } == true &&
                it.config.contains(SemanticsActions.OnClick)
        }
        fun hasText(text: String) = nodes(environment).any { s4HasLabel(it, text) }
        fun bubbleOrNull(body: String): SemanticsNode? = nodes(environment).singleOrNull {
            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" && s4HasLabel(it, body)
        }
        fun bubble(body: String): SemanticsNode = assertNotNull(bubbleOrNull(body), "前提：真实消息表面语义存在：$body")
        fun axis() = s4List(environment).config[SemanticsProperties.VerticalScrollAxisRange]
        fun scrollBy(dy: Float): Boolean = requireNotNull(s4List(environment).config[SemanticsActions.ScrollBy].action).invoke(0f, dy)
        fun pixel(x: Float, y: Float): Int = environment.scene.render(scope.testScheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { it.getColor(floor(x).toInt(), floor(y).toInt()) }
        }
        fun tap(label: String) {
            val target = nodes(environment).first { s4HasLabel(it, label) && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot
            environment.press(target.center); environment.advanceFrameBy(8); environment.release(target.center); consume()
        }
        fun frame(current: MessageSendFlight): Rect =
            flight.frame(current, MESSAGE_BUBBLE_CONTENT_PADDING_X.value * density, MESSAGE_BUBBLE_CONTENT_PADDING_Y.value * density)
        private fun capture(): Snapshot {
            val field = field().boundsInRoot
            val glyphs = Rect(field.left + 12f * density, field.top + 12f * density,
                field.left + 12f * density + 64f, field.top + 12f * density + 22f)
            val patch = s3SmallPatch(environment, scope.testScheduler, glyphs)
            val background = patch.pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
            return Snapshot(fieldText(), glyphs, patch, background)
        }
        /** Types and taps through the real field/send button; returns the last frame still showing the draft. */
        fun sendThroughComposer(body: String): Snapshot {
            assertTrue(requireNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(body)))
            frames(30)
            val send = assertNotNull(sendAction(), "前提：有正文后发送槽已出现").boundsInRoot
            environment.press(send.center); environment.advanceFrameBy(8); environment.release(send.center)
            var last = capture()
            for (step in 0 until 8) {
                environment.advanceFrameBy(0)
                if (fieldText() != body) break
                last = capture()
            }
            return last
        }
        fun untilHandoff(): Snapshot {
            for (step in 0 until 12) {
                if (fieldText().isEmpty()) return capture()
                environment.advanceFrameBy(16)
            }
            fail("交接前提：排队记录出现后输入框须交出正文")
        }
        fun inkRatio(current: S3SmallPatch, reference: S3SmallPatch, background: Int): Double {
            val core = reference.pixels.indices.filter { s3RgbDistance(reference.pixels[it], background) >= 48 }
            assertTrue(core.size >= 6, "像素前提：原输入字形须有足够高对比点")
            return core.map { s3RgbDistance(current.pixels[it], background).toDouble() / s3RgbDistance(reference.pixels[it], background) }.average()
        }
        suspend fun receive(body: String) {
            transport.emitReceived("f-live-${++serial}", SYNTH_NUMBER, body, NOW_MS / 1000 + serial)
            scope.testScheduler.runCurrent()
            assertEquals(HistoryChangeReason.LIVE_INSERT, ready().window?.change?.reason)
            consume()
        }
        override fun close() { transportGate.complete(Unit); environment.close(); source.close(); scope.testScheduler.runCurrent(); repository.close() }
        companion object {
            suspend fun create(scope: TestScope, count: Int, scale: Float = 1f, failSend: Boolean = false): Fixture {
                val repository = FakeMessageRepository(); val owner = assertNotNull(ownerFor(ICCID_A))
                repeat(count) { i -> repository.insertIncomingIfAbsent(owner, "f-old-$i", "f-wire-$i", SYNTH_NUMBER,
                    "F历史$i", NOW_MS - (count - i) * 1000L, NOW_MS) }
                val gate = CompletableDeferred<Unit>()
                val transport = FakeEngineMessageTransport(sendResult = if (failSend)
                    EngineMessagePersistenceFixtures.sendError(4001, "synthetic failure")
                    else EngineMessagePersistenceFixtures.sendOk(MO_ID, 202, 1), requestGate = gate)
                val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
                scope.testScheduler.runCurrent(); val identity = StoredMessageConversation(owner, SYNTH_NUMBER.drop(1))
                source.selectConversation(identity.handle.value); scope.testScheduler.runCurrent()
                return Fixture(scope, repository, transport, source, identity, gate, scale).also { f ->
                    f.frames(80)
                    val axis = f.axis()
                    assertEquals(axis.maxValue(), axis.value(), .5f, "前提：真实首次到底已完成")
                }
            }
        }
    }

    private class SlotRig(scheduler: TestCoroutineScheduler, scale: Float) : AutoCloseable {
        val body = mutableStateOf("")
        var sendCalls = 0
        private val e = MotionTestEnvironment(scheduler, 280, 400, durationScale = scale) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    Column(Modifier.fillMaxWidth().background(LocalSoftVoWiFiColors.current.canvas)) {
                        MessageComposer(body.value, MessageComposeSpec(MessageComposeMode.ENABLED, null),
                            MessageImeOwner.COMPOSE_COMPOSER_PADDING, {}, false, body.value.isNotBlank(),
                            { body.value = it }, { sendCalls++ }, floating = true)
                    }
                }
            }
        }
        init { frames(40) }
        fun frames(n: Int) { repeat(n) { e.advanceFrameBy(16) } }
        fun consume() { repeat(4) { e.advanceFrameBy(0) } }
        private fun field(): SemanticsNode = nodes(e).single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true &&
                it.config.contains(SemanticsProperties.EditableText)
        }
        fun fieldWidth() = field().boundsInRoot.width
        fun edit(value: String) {
            assertTrue(requireNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        }
        fun send(): SemanticsNode? = nodes(e).singleOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("发送短信") == true &&
                it.config.contains(SemanticsActions.OnClick)
        }
        override fun close() { e.close() }
    }
}
