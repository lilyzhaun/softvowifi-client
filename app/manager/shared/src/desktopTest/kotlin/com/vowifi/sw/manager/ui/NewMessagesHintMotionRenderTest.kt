@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sourceOf
import com.vowifi.sw.manager.desktop.s4HasLabel
import com.vowifi.sw.manager.desktop.s4List
import com.vowifi.sw.manager.desktop.s4Nodes as threadNodes
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.HistoryChangeReason
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual source, simulated Received and native LazyColumn; never a synthetic visibility flag. */
class NewMessagesHintMotionRenderTest {
    @Test fun distantHintApproachesWithinOneScreenBeforeTheFinalAnimation() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
        val owner = assertNotNull(ownerFor(ICCID_A))
        val identity = StoredMessageConversation(owner, SYNTH_NUMBER.drop(1))
        val records = (0 until 500).map { MessageRecord.Incoming("far-$it", SYNTH_NUMBER, "合成远距消息$it", NOW_MS + it * 1000L) }
        var state by mutableStateOf(MessageUiState.Ready(records, MessageOrigin.ENGINE, ownerId = owner))
        MotionTestEnvironment(testScheduler, 420, 900, scale) { s4SmsHost {
            MessageThreadScreen(identity.handle, state, { _, _ -> error("不得发送") })
        } }.use { e ->
            fun frames(n: Int) { repeat(n) { e.advanceFrameBy(16) } }
            fun indices(): List<Int> {
                val viewport = s4List(e).boundsInRoot
                return threadNodes(e).filter { it.boundsInRoot.height > 0f && it.boundsInRoot.top >= viewport.top &&
                    it.boundsInRoot.bottom <= viewport.bottom }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .mapNotNull { Regex("合成远距消息([0-9]+)").matchEntire(stripMessageDisplayJoiners(it.text))?.groupValues?.get(1)?.toInt() }
                    .distinct().sorted()
            }
            frames(80)
            assertTrue(indices().lastOrNull() == 499, "前提：实际最后一条499完整可见，不把scroll轴值当条目index：${indices()}")
            val scroll = requireNotNull(s4List(e).config[SemanticsActions.ScrollBy].action)
            assertTrue(scroll(0f, -100_000f)); frames(40)
            assertTrue(indices().isNotEmpty() && indices().max() < 30, "前提：实际长线程已远离底部超过三屏；可见=${indices()}")
            state = state.copy(records = records + MessageRecord.Incoming("far-new", SYNTH_NUMBER, "合成远距最新消息", NOW_MS + 501_000))
            frames(40)
            val hint = threadNodes(e).single { s4HasLabel(it, "查看新消息") && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(indices().isNotEmpty() && indices().max() < 30, "离底新消息不抢滚动")
            e.press(hint.boundsInRoot.center); e.advanceFrameBy(8); e.release(hint.boundsInRoot.center)
            repeat(4) { e.advanceFrameBy(0) }; frames(2)
            val approached = indices()
            assertTrue(approached.isNotEmpty() && approached.min() >= 500 - approached.size - 2,
                "超过三屏应先靠近目标约一屏内；真实可见条目=$approached，不能用不等于index的滚动轴估值冒靠近")
            frames(100)
            assertTrue(threadNodes(e).any { s4HasLabel(it, "合成远距最新消息") && it.boundsInRoot.height > 0f })
        }
        }
    } }

    @Test fun actualReceivedOffBottomHasAnEntranceAndItsTapStartsScrollingWithoutWaitingForExit() = onMotionTestThread { runTest {
        var normalTerminalTop: Float? = null
        for (scale in listOf(1f, 0f)) {
            val repository = FakeMessageRepository()
            val owner = assertNotNull(ownerFor(ICCID_A))
            repeat(40) { i -> repository.insertIncomingIfAbsent(owner, "hint-old-$i", "hint-wire-$i",
                SYNTH_NUMBER, "合成历史$i", NOW_MS - (40 - i) * 1_000L, NOW_MS) }
            val transport = FakeEngineMessageTransport()
            val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
            val identity = StoredMessageConversation(owner, SYNTH_NUMBER.drop(1))
            try {
                testScheduler.runCurrent(); source.selectConversation(identity.handle.value); testScheduler.runCurrent()
                val original = assertIs<MessageUiState.Ready>(source.state.value).records.map { it.id }
                var density = 0f
                MotionTestEnvironment(testScheduler, 420, 900, durationScale = scale) {
                    s4SmsHost {
                        density = LocalDensity.current.density
                        val state by source.state.collectAsState()
                        MessageThreadScreen(identity.handle, state, { _, _ -> error("G2不得发送") })
                    }
                }.use { e ->
                    fun frames(n: Int) { repeat(n) { e.advanceFrameBy(16) } }
                    fun consume() { repeat(4) { e.advanceFrameBy(0) } }
                    fun hint(): SemanticsNode? = threadNodes(e).singleOrNull {
                        s4HasLabel(it, "查看新消息") && it.config.contains(SemanticsActions.OnClick)
                    }
                    fun position() = s4List(e).config[SemanticsProperties.VerticalScrollAxisRange].value()
                    fun visible(text: String): Boolean {
                        val viewport = s4List(e).boundsInRoot
                        return threadNodes(e).any { n -> s4HasLabel(n, text) && n.boundsInRoot.height > 0f &&
                            n.boundsInRoot.top >= viewport.top && n.boundsInRoot.bottom <= viewport.bottom }
                    }
                    frames(80)
                    assertTrue(visible("合成历史39"), "前提：实际首次布局已在最新，不靠猜index")
                    assertNull(hint())
                    val atBottom = position()
                    assertTrue(requireNotNull(s4List(e).config[SemanticsActions.ScrollBy].action).invoke(0f, -900f))
                    frames(40)
                    val offBottom = position()
                    assertTrue(offBottom < atBottom - 2f && !visible("合成历史39"),
                        "前提：真实LazyColumn已离底至少两项且旧最新不可见；失败不计动效RED")
                    assertNull(hint(), "滚离底部本身不能伪造新消息提示")
                    transport.emitReceived("hint-new-wire", SYNTH_NUMBER, "合成最新消息", NOW_MS / 1_000 + 1)
                    testScheduler.runCurrent()
                    val ready = assertIs<MessageUiState.Ready>(source.state.value)
                    val added = ready.records.single { it.engineSmsId == "hint-new-wire" }
                    assertTrue(added.id !in original)
                    val change = assertNotNull(ready.window?.change)
                    assertEquals(HistoryChangeReason.LIVE_INSERT, change.reason)
                    assertTrue(added.id in change.ids, "前提：真实Received/读回已发布新稳定id")
                    consume()
                    val first = assertNotNull(hint(), "非底部新消息必须真实创建提示").boundsInRoot
                    frames(4)
                    val middle = assertNotNull(hint()).boundsInRoot
                    frames(40)
                    val terminal = assertNotNull(hint()).boundsInRoot
                    assertEquals(offBottom, position(), .01f, "非底部收信不得自动抢到最新")
                    if (scale == 0f) {
                        assertEquals(terminal.top, first.top, .5f, "zero首个消费帧须与正常终态位置一致")
                        assertEquals(assertNotNull(normalTerminalTop), terminal.top, .5f, "正常/zero须有相同实际终位")
                    } else {
                        normalTerminalTop = terminal.top
                        assertTrue(first.top - terminal.top in (4f * density)..((MotionTokens.OffsetSmall.value + .5f) * density),
                            "G2：真实提示首帧须从下方至少4dp进入，不是if直接瞬现")
                        assertTrue(middle.top < first.top - .25f && middle.top > terminal.top + .25f,
                            "G2：64ms必须实际处于进入轨迹中，不只验最终可见")
                    }
                    val target = assertNotNull(hint()).boundsInRoot.center
                    val beforeTap = position()
                    e.press(target); e.advanceFrameBy(8); e.release(target); consume(); frames(2)
                    assertTrue(position() > beforeTap + .01f,
                        "真实提示点击须立即开始原滚到底动作，不能等待200ms视觉退出")
                    var disabledExitFrames = 0
                    repeat(100) {
                        frames(1)
                        hint()?.let { if (it.config.getOrNull(SemanticsProperties.Disabled) != null) disabledExitFrames++ }
                    }
                    if (scale > 0f) assertTrue(disabledExitFrames > 0,
                        "退场必须保留实际提示绘制帧，但立即撤销点击；不能再用if一帧卸载")
                    assertTrue(visible("合成最新消息"), "实际滚动完成后新消息须可见")
                    assertNull(hint(), "到最新后提示必须最终退出，不以末页当中间轨迹证明")
                    assertTrue(transport.requests.isEmpty(), "只接模拟Received，不得调用任何发送RPC")
                }
            } finally { source.close(); testScheduler.runCurrent(); repository.close() }
        }
    } }
}
