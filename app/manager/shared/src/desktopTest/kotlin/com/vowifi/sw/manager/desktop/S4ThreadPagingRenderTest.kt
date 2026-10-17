@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.desktop

import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.*

class S4ThreadPagingRenderTest {
    @Test fun initialBottomCompletesBeforeNearStartAutoReadAndAnchorSurvivesMiddleFrames() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = true).use { rig ->
            rig.gate = CompletableDeferred()
            MotionTestEnvironment(testScheduler, 420, 900) { rig.Content(true) }.use { e ->
                e.s4Frames(12)
                assertNotNull(s4Visible(e, "p-120", inList = true), "前提：首次到底已实际完成")
                assertEquals(0, rig.olderCalls); assertEquals(0, rig.olderReads)
                var anchor: Pair<String, Float>? = null
                rig.atOlderRequest = { anchor = s4Anchor(e) }
                s4WheelUntil(e, -8f) { rig.olderCalls > 0 || s4Visible(e, "加载更早消息", inList = true) != null }
                assertEquals(1, rig.olderCalls, "R6：近首视口自动读取，不能依赖手动按钮")
                val saved = assertNotNull(anchor)
                assertEquals(1, rig.olderReads); assertTrue(rig.thread.window!!.loading)
                e.s4Frames(24); assertEquals(1, rig.olderCalls)
                rig.gate!!.complete(Unit); e.advanceFrameBy(0); e.s4Frames(2)
                assertEquals(100, rig.thread.records.size)
                assertEquals(saved.second, s4Visible(e, saved.first, inList = true)!!.boundsInRoot.top, 2f,
                    "中间帧：同一真实消息 key 的 offset 不可被 prepend/补位移动")
                e.s4Frames(24)
                assertEquals(saved.second, s4Visible(e, saved.first, inList = true)!!.boundsInRoot.top, 2f)
                assertNull(s4Visible(e, "p-120", inList = true)); assertEquals(1, rig.olderCalls)
            }
        }
    } }

    @Test fun olderCompletionDuringSearchDoesNotRestoreTimelineAnchorIntoSearchState() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = true).use { rig ->
            rig.gate = CompletableDeferred()
            MotionTestEnvironment(testScheduler, 420, 900) { rig.Content(true) }.use { e ->
                e.s4Frames(12)
                var normal: Pair<String, Float>? = null
                rig.atOlderRequest = { normal = s4Anchor(e) }
                s4StartOlderThroughUi(e, rig)
                val savedNormal = assertNotNull(normal)
                s4Tap(e, "搜索会话"); e.s4Frames(16)
                // 真实树中搜索框在 composer 前；只读 composer 仍可编辑草稿，不能错误假定只有一个 SetText。
                val field = s4Nodes(e).first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("p-7")))
                e.s4Frames(16)
                assertNotNull(s4Visible(e, "仅搜索已加载消息 · 9 条"), "前提：真实搜索框已消费查询")
                s4WheelUntil(e, 3f) { s4List(e).config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f }
                val savedSearch = s4Anchor(e)
                fun traceSearch(phase: String) {
                    val list = s4List(e)
                    val points = s4Nodes(e).mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)
                        ?.firstOrNull { it.text.matches(Regex("p-\\d+")) }?.let { "${it.text}@${node.boundsInRoot.top}" } }.take(6)
                    val heads = s4Nodes(e).filter { s4HasLabel(it, "正在加载短信历史") || s4HasLabel(it, "加载更早消息") ||
                        s4HasLabel(it, "已显示全部历史") }.map { it.boundsInRoot }.take(2)
                    println("S4_SMS_SEARCH|phase=$phase|records=${rig.thread.records.size}|loading=${rig.thread.window!!.loading}|" +
                        "viewport=${list.boundsInRoot}|axis=${list.config[SemanticsProperties.VerticalScrollAxisRange].value()}|heads=$heads|messages=$points")
                }
                traceSearch("beforeOlderCompletion")
                rig.gate!!.complete(Unit); e.advanceFrameBy(0); e.s4Frames(2)
                traceSearch("afterOlderCompletion32ms")
                assertEquals(100, rig.thread.records.size)
                assertEquals(savedSearch.second, s4Visible(e, savedSearch.first, inList = true)!!.boundsInRoot.top, 2f,
                    "搜索列表拥有自己的 key/offset，不能消费普通时间线的 pending anchor")
                e.s4Frames(16); assertEquals(1, rig.olderCalls); assertEquals(1, rig.olderReads)
                s4Tap(e, "关闭会话搜索"); e.s4Frames(24)
                assertEquals(savedNormal.second, s4Visible(e, savedNormal.first, inList = true)!!.boundsInRoot.top, 2f)
                assertNull(s4Visible(e, "p-120", inList = true))
            }
        }
    } }

    @Test fun firstLoadDoesNotAnimateOnlyLatestAndPagedBubbleIsOpaqueOnImmediateReveal() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = true, count = 100).use { rig ->
            rig.gate = CompletableDeferred()
            MotionTestEnvironment(testScheduler, 420, 900) { rig.Content(true) }.use { e ->
                e.s4Frames(2)
                val latest = s4Visible(e, "p-99", inList = true)!!.boundsInRoot
                val previous = s4Visible(e, "p-98", inList = true)!!.boundsInRoot
                val a = s3SmallPatch(e, testScheduler, latest); val b = s3SmallPatch(e, testScheduler, previous)
                e.s4Frames(24)
                assertEquals(latest.top, s4Visible(e, "p-99", inList = true)!!.boundsInRoot.top, .5f)
                assertTrue(abs(s4InkRatio(a, s3SmallPatch(e, testScheduler, latest)) -
                    s4InkRatio(b, s3SmallPatch(e, testScheduler, previous))) < .08,
                    "首载可有整个列表淡入，但不能只让 latestId 单项重新入场")
                s4StartOlderThroughUi(e, rig)
                rig.gate!!.complete(Unit); e.advanceFrameBy(0); e.s4Frames(2)
                assertNull(rig.thread.window!!.older)
                // 仅定位刚并入的页：消费真实公开无障碍滚动入口，不调用 LazyListState/private UI 方法。
                assertTrue(s4List(e).config[SemanticsActions.ScrollToIndex].action!!.invoke(2))
                e.advanceFrameBy(0); e.s4Frames(2)
                val region = s4Visible(e, "p-0", inList = true)!!.boundsInRoot
                val middle = s3SmallPatch(e, testScheduler, region)
                e.s4Frames(24)
                assertEquals(region.top, s4Visible(e, "p-0", inList = true)!!.boundsInRoot.top, .5f)
                assertTrue(s4InkRatio(middle, s3SmallPatch(e, testScheduler, region)) > .97,
                    "分页并入的新 id 在首个可见中间帧不得淡入")
                assertEquals(1, rig.olderCalls); assertEquals(1, rig.olderReads)
            }
        }
    } }
}

/** 后两项隔离旧锚点行为；缺自动入口时只允许真实指针触发旧按钮，第一项仍严格要求自动读取。 */
private fun s4StartOlderThroughUi(e: MotionTestEnvironment, rig: S4SmsHistoryRig) {
    s4WheelUntil(e, -8f) { rig.olderCalls > 0 || s4Visible(e, "加载更早消息", inList = true) != null }
    println("S4_SMS_OLDER|phase=beforeManual|calls=${rig.olderCalls}|reads=${rig.olderReads}|viewport=${s4List(e).boundsInRoot}")
    if (rig.olderCalls == 0) s4Tap(e, "加载更早消息")
    e.advanceFrameBy(0); e.s4Frames(2)
    println("S4_SMS_OLDER|phase=afterManual|calls=${rig.olderCalls}|reads=${rig.olderReads}|loading=${rig.thread.window!!.loading}")
    assertEquals(1, rig.olderCalls, "命中前提：必须实际消费原 onLoadOlder")
    assertEquals(1, rig.olderReads); assertTrue(rig.thread.window!!.loading)
}
