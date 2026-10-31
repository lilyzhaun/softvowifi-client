@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.ConversationHandle
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NewMessageConversationLookupRenderTest {
    @Test fun rapidInputOnlyQueriesTheFinalNumberAfterThreeHundredMillisecondsInBothAnimationModes() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) Rig(testScheduler, scale).use { f ->
            for (partial in listOf("1", "10", "100", "1008")) {
                f.edit(0, partial); f.e.advanceFrameBy(40)
            }
            f.edit(0, PEER)
            assertTrue(f.queries.isEmpty(), "连续输入不逐键读取仓库")
            f.e.advanceFrameBy(299)
            assertTrue(f.queries.isEmpty(), "动画关闭也不能缩短真实查询停顿")
            assertFalse(f.hasText(NOTICE))
            f.e.advanceFrameBy(1); f.consume(); f.frames(60)
            assertEquals(listOf(OWNER to PEER), f.queries)
            assertTrue(f.hasText(NOTICE)); assertTrue(f.hasText("打开会话"))
            assertEquals(0, f.sends); assertEquals(0, f.selections); assertEquals(0, f.opens)
        }
    } }

    @Test fun noticeUsesReversibleHeightTransitionAndZeroAnimationReachesTheSameLayout() = onMotionTestThread { runTest {
        var normalDisplacement = 0f
        for (scale in listOf(1f, 0f)) Rig(testScheduler, scale).use { f ->
            val baseline = f.lineTop()
            f.edit(0, PEER); f.pause(); f.frames(4)
            val middle = f.lineTop()
            f.frames(60)
            val expanded = f.lineTop()
            assertTrue(expanded > baseline + 24f, "前提：真实提示及动作确实让位")
            if (scale == 1f) {
                normalDisplacement = expanded - baseline
                assertTrue(middle > baseline + .5f && middle < expanded - .5f, "L7 入场必须有实际高度中间帧")
            } else {
                assertEquals(expanded, middle, .5f)
                assertEquals(normalDisplacement, expanded - baseline, .5f)
            }
            f.edit(0, "10087")
            assertFalse(f.hasText(NOTICE), "旧提示语义立即撤权，退出绘制不冒当前结果")
            if (scale == 1f) assertEquals(expanded, f.lineTop(), .5f, "同刻反向不跳终值")
            f.frames(4)
            val shrinking = f.lineTop()
            if (scale == 1f) assertTrue(shrinking > baseline + .5f && shrinking < expanded - .5f, "L7 退出必须有实际高度中间帧")
            else assertEquals(baseline, shrinking, .5f)
            f.frames(60)
            assertEquals(baseline, f.lineTop(), .5f, "退出后不残留提示高度")
            assertFalse(f.hasText("打开会话")); assertEquals(0, f.opens)
        }
    } }

    @Test fun changingTheNumberImmediatelyRevokesTheOldActionAndTheReplacementWaitsAgain() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) Rig(testScheduler, scale).use { f ->
            f.edit(0, PEER); f.pause(); f.frames(60)
            val old = f.openAction().config[SemanticsActions.OnClick].action!!
            f.edit(0, "10087")
            assertFalse(f.hasText(NOTICE)); old.invoke(); assertEquals(0, f.opens)
            assertEquals(1, f.queries.size)
            f.e.advanceFrameBy(100)
            f.edit(0, PEER)
            f.e.advanceFrameBy(299)
            assertEquals(1, f.queries.size); assertFalse(f.hasText(NOTICE))
            f.e.advanceFrameBy(1); f.consume(); f.frames(60)
            assertEquals(listOf(OWNER to PEER, OWNER to PEER), f.queries)
            assertTrue(f.hasText(NOTICE))
        }
    } }

    @Test fun lookupRequiresAnActiveBoundValidRecipientAndNewInputCancelsAnInFlightQuery() = onMotionTestThread { runTest {
        Rig(testScheduler, 1f).use { f ->
            f.active.value = false; f.recipient.value = PEER; f.consume(); f.e.advanceFrameBy(500)
            f.owner.value = null; f.active.value = true; f.consume(); f.e.advanceFrameBy(500)
            f.recipient.value = "+"; f.owner.value = OWNER; f.consume(); f.e.advanceFrameBy(500)
            assertTrue(f.queries.isEmpty(), "失活、未绑定、非法号码均不查询")
            f.hold = true; f.edit(0, PEER); f.pause()
            assertEquals(1, f.queries.size); assertEquals(0, f.cancellations)
            f.edit(0, "10087")
            assertEquals(1, f.cancellations, "新输入须取消已开始的挂起查询")
            assertFalse(f.hasText(NOTICE)); assertEquals(1, f.queries.size)
            f.hold = false; f.pause()
            assertEquals(listOf(OWNER to PEER, OWNER to "10087"), f.queries)
            assertFalse(f.hasText(NOTICE)); assertEquals(0, f.opens)
        }
    } }

    @Test fun bodyPendingAndInactiveGatesRemainAndOpeningIsNotDebounced() = onMotionTestThread { runTest {
        Rig(testScheduler, 1f).use { f ->
            f.edit(0, PEER); f.pause(); f.frames(60)
            val old = f.openAction().config[SemanticsActions.OnClick].action!!
            f.edit(1, "synthetic unsent draft")
            assertTrue(f.hasText(NOTICE)); assertFalse(f.hasText("打开会话"))
            old.invoke(); assertEquals(0, f.opens)
            assertEquals("synthetic unsent draft", f.body.value)
            assertEquals(1, f.queries.size, "正文变化不重新查询")
            f.edit(1, ""); f.state.value = f.state.value.copy(sendBusy = true); f.consume()
            assertTrue(f.openAction().config.contains(SemanticsProperties.Disabled))
            s4Tap(f.e, f.openAction().boundsInRoot.center); assertEquals(0, f.opens)
            f.state.value = f.state.value.copy(sendBusy = false); f.active.value = false; f.consume()
            old.invoke(); assertEquals(0, f.opens)
            f.active.value = true; f.consume(); f.pause(); f.frames(60)
            s4Tap(f.e, f.openAction().boundsInRoot.center)
            assertEquals(1, f.opens, "真实点击立即打开，不给用户动作加 300ms 延迟")
            assertEquals(0, f.sends); assertEquals(0, f.selections)
        }
    } }

    private class Rig(scheduler: TestCoroutineScheduler, scale: Float) : AutoCloseable {
        val recipient = mutableStateOf("")
        val body = mutableStateOf("")
        val owner = mutableStateOf<SimOwnerId?>(OWNER)
        val active = mutableStateOf(true)
        val state = mutableStateOf(MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, composerEnabled = true, ownerId = OWNER))
        val queries = mutableListOf<Pair<SimOwnerId, String>>()
        var hold = false
        var cancellations = 0
        var opens = 0
        var sends = 0
        var selections = 0
        private val lookup: suspend (SimOwnerId, String) -> ConversationHandle? = { owner, number ->
            queries += owner to number
            if (hold) try { awaitCancellation() } finally { cancellations++ }
            if (number == PEER) StoredMessageConversation(owner, PEER).handle else null
        }
        val e = MotionTestEnvironment(scheduler, 420, 900, durationScale = scale) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalAppRouteActive provides active.value) {
                    NewMessageScreen(state.value, recipient.value, body.value,
                        { recipient.value = it }, { body.value = it }, { recipient.value = ""; body.value = "" },
                        { _, _ -> sends++ }, draftOwner = owner.value, onSelectHistoryOwner = { selections++ },
                        findConversation = lookup, onOpenConversation = { opens++ })
                }
            }
        }
        init { frames(40) }
        fun consume() { repeat(4) { e.advanceFrameBy(0) } }
        fun frames(count: Int) { repeat(count) { e.advanceFrameBy() } }
        fun pause() { e.advanceFrameBy(300); consume() }
        fun hasText(text: String) = s4Nodes(e, false).any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
        fun openAction(): SemanticsNode = s4Actions(e, "打开会话").single()
        fun lineTop() = s4Nodes(e).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "已绑定线路" }.boundsInRoot.top
        fun edit(index: Int, value: String) {
            val fields = s4Nodes(e, false).filter { it.config.contains(SemanticsProperties.EditableText) }.takeLast(2)
            assertEquals(2, fields.size)
            assertTrue(fields[index].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
            consume()
        }
        override fun close() { e.close() }
    }

    companion object {
        private const val PEER = "10086"
        private const val NOTICE = "此线路已有与该号码的会话"
        private val OWNER = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    }
}
