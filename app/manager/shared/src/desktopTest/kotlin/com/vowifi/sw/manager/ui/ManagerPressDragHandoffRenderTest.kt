@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 真实父Pager接管后撤销子页gate不能夺回手势；不手发Interaction或强设页码。 */
class ManagerPressDragHandoffRenderTest {
    @Test fun visibleInactiveRowMustNotBlockANewParentDragWhileThePageIsSettling() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 30.seconds) {
            lateinit var pager: PagerState
            var clicks = 0
            MotionTestEnvironment(testScheduler, 400, 300, dispatcher = dispatcher) {
                val state = rememberPagerState(initialPage = 0) { 2 }
                SideEffect { pager = state }
                HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { page ->
                    val source = remember { MutableInteractionSource() }
                    // Outgoing visible rows are intentionally inactive after root selection.
                    // Their business action stays disabled; the ancestor still owns paging.
                    Box(Modifier.fillMaxSize().managerPressInputGate(page == 1)
                        .clickable(interactionSource = source, indication = null, enabled = page == 1) { clicks++ })
                }
            }.use { e ->
                fun frame(count: Int = 1) { repeat(count) { e.advanceFrameBy(16) } }
                frame(4)
                e.press(Offset(350f, 150f)); frame()
                repeat(15) { i -> e.move(Offset(350f - 20f * (i + 1), 150f)); frame() }
                val heldPosition = pager.currentPage + pager.currentPageOffsetFraction
                e.release(Offset(50f, 150f)); frame(80)
                assertEquals(0, clicks)
                assertTrue(heldPosition > .5f, "an inactive child must not swallow the new parent drag: position=$heldPosition")
                assertEquals(1, pager.currentPage)
                assertEquals(0f, pager.currentPageOffsetFraction, .01f)
            }
        }
    }

    @Test fun targetPageRevocationDoesNotCancelTheDragAlreadyOwnedByParentPager() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 30.seconds) {
            lateinit var pager: PagerState
            val source = MutableInteractionSource()
            val interactions = mutableListOf<Interaction>()
            var clicks = 0
            var parentConsumedMoves = 0
            var gateClosed = false
            var progressAtClosure = 0f
            var consumedBeforeClosure = 0
            MotionTestEnvironment(testScheduler, 400, 300, dispatcher = dispatcher) {
                val state = rememberPagerState(initialPage = 1) { 2 }
                SideEffect { pager = state }
                LaunchedEffect(source) { source.interactions.collect { interactions += it } }
                HorizontalPager(state = state, modifier = Modifier.fillMaxSize()) { page ->
                    val active = state.targetPage == page
                    val rowSource = if (page == 1) source else remember { MutableInteractionSource() }
                    SideEffect {
                        if (page == 1 && !active && !gateClosed) {
                            gateClosed = true
                            progressAtClosure = 1f - (state.currentPage + state.currentPageOffsetFraction)
                            consumedBeforeClosure = parentConsumedMoves
                        }
                    }
                    Box(Modifier.fillMaxSize()
                        // Main在子clickable之后、父Pager之前；Final在父Pager之后、子gate之前。
                        // 仅观察真实移动：子节点未消费而父级随后消费；不把down/up或自身撤销算交接。
                        .pointerInput(page) {
                            awaitPointerEventScope {
                                while (true) {
                                    val main = awaitPointerEvent(PointerEventPass.Main)
                                    val availableMoves = main.changes.filter {
                                        it.pressed && it.previousPressed && it.position != it.previousPosition && !it.isConsumed
                                    }.map { it.id }.toSet()
                                    val final = awaitPointerEvent(PointerEventPass.Final)
                                    if (page == 1 && final.changes.any { it.id in availableMoves && it.isConsumed }) {
                                        parentConsumedMoves++
                                    }
                                }
                            }
                        }
                        .managerPressInputGate(active)
                        .clickable(interactionSource = rowSource, indication = null, enabled = active) { clicks++ })
                }
            }.use { e ->
                fun frames(count: Int = 1) { repeat(count) { e.advanceFrameBy(16) } }
                frames(4)
                assertEquals(1, pager.currentPage)
                e.press(Offset(60f, 150f)); frames(15)
                val press = interactions.filterIsInstance<PressInteraction.Press>().single()
                repeat(15) { i ->
                    e.move(Offset(60f + 20f * (i + 1), 150f)); frames()
                }
                val heldProgress = 1f - (pager.currentPage + pager.currentPageOffsetFraction)
                assertTrue(gateClosed, "前提：真实targetPage变化已自动撤销原页gate")
                assertTrue(progressAtClosure > .1f, "撤gate前父Pager必须已实际位移，实际=$progressAtClosure")
                assertTrue(consumedBeforeClosure > 0, "撤gate前须已观察父级消费真实位置变化")
                e.release(Offset(360f, 150f)); frames(80)
                assertEquals(0, clicks, "交给父Pager的手势不能触发原行或邻页业务")
                assertSame(press, interactions.filterIsInstance<PressInteraction.Cancel>().single().press)
                assertEquals(1, interactions.filterIsInstance<PressInteraction.Cancel>().size)
                assertTrue(interactions.none { it is PressInteraction.Release }, "拖动取消不能变成成功Release")
                assertTrue(heldProgress > progressAtClosure + .1f,
                    "撤gate后父拖动仍须继续：关闭时=$progressAtClosure，抬手前=$heldProgress")
                assertEquals(0, pager.currentPage, "父Pager必须到达真实拖动目标，不能被子gate取消回原页")
                assertEquals(0f, pager.currentPageOffsetFraction, .01f)
            }
        }
    }
}
