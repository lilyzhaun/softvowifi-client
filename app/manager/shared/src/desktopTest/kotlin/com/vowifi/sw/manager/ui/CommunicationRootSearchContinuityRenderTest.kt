@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.data.FakeCallHistoryRepository
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.historyWrite
import com.vowifi.sw.manager.desktop.S4SmsHistoryRig
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.CallAudioState
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationRootSearchContinuityRenderTest {
    @Test fun realInboxQueryEditsAndReadbacksNeverFadeTheUnchangedLineEntry() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = false, count = 3).use { rig ->
            MotionTestEnvironment(testScheduler, 420, 900) { rig.Content(thread = false) }.use { environment ->
                s4Frames(environment, 40)
                openSearch(environment, "搜索短信")
                val title = rig.selection.historyChoices.value.lines.single().lineTitle()
                val region = sampleRegion(environment, title)
                val baseline = s3SmallPatch(environment, testScheduler, region)
                assertVisibleInk(baseline)
                for (query in QUERIES) {
                    editSearch(environment, query)
                    assertEquals(query, rig.inbox.page.query, "真实历史源必须立即收到查询，不能靠延迟输入消除闪烁")
                    assertEquals(region, sampleRegion(environment, title), "查询不能重排原线路入口")
                    assertEquals(0, s4Difference(baseline, s3SmallPatch(environment, testScheduler, region)),
                        "每次输入/删除及权威结果读回都不能使同一线路栏淡出再出现")
                }
                assertEquals(3, rig.inbox.page.items.size, "清空后必须恢复真实历史，不缓存错误查询结果")
                assertEquals(3, rig.stored.listByOwner(rig.owner).size)
            }
        }
    } }

    @Test fun actualCallsRootQueryEditsNeverFadeTheUnchangedLineEntry() = onMotionTestThread { runTest {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val stored = FakeCallHistoryRepository()
        stored.upsert(owner, historyWrite(correlation = "search-only", peer = "155501",
            startedAtMs = 0, connectedAtMs = 1000, endedAtMs = 2000, durationSec = 1, outcome = CallOutcome.COMPLETED))
        val history = CallHistoryState.Ready(stored.listByOwner(owner), owner)
        MotionTestEnvironment(testScheduler, 420, 900) {
            s4SmsHost {
                CallsScreen(CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE), true,
                    {}, history, {}, {}, {}, {}, {}, lineContent = {
                        CommunicationLineChip("合成线路", "通话记录线路", {})
                    })
            }
        }.use { environment ->
            s4Frames(environment, 40)
            openSearch(environment, "搜索通话记录")
            val region = sampleRegion(environment, "合成线路")
            val baseline = s3SmallPatch(environment, testScheduler, region)
            assertVisibleInk(baseline)
            for (query in QUERIES) {
                editSearch(environment, query)
                assertEquals(region, sampleRegion(environment, "合成线路"))
                assertEquals(0, s4Difference(baseline, s3SmallPatch(environment, testScheduler, region)),
                    "通话本地过滤每次改字不能重置整张列表的绘制透明度")
            }
            assertEquals(1, stored.listByOwner(owner).size)
            assertEquals(1, history.records.size)
        }
    } }

    private fun openSearch(environment: MotionTestEnvironment, label: String) {
        s4Tap(environment, s4Action(environment, label).boundsInRoot.center)
        s4Frames(environment, 20)
        assertEquals(1, s4Nodes(environment).count { it.config.contains(SemanticsActions.SetText) })
    }

    private fun editSearch(environment: MotionTestEnvironment, query: String) {
        val editor = s4Nodes(environment).single { it.config.contains(SemanticsActions.SetText) }
        assertTrue(requireNotNull(editor.config[SemanticsActions.SetText].action).invoke(AnnotatedString(query)))
        // Drain only the real query effects at this instant, then inspect the next input frame.
        repeat(6) { environment.advanceFrameBy(0) }
        environment.advanceFrameBy(8)
    }

    private fun sampleRegion(environment: MotionTestEnvironment, label: String): Rect {
        val bounds = s3TextBounds(s4Nodes(environment), label)
        return Rect(bounds.left, bounds.top, min(bounds.right, bounds.left + 80f), bounds.bottom)
    }

    private fun assertVisibleInk(patch: S3SmallPatch) {
        assertTrue(patch.pixels.count { s3RgbDistance(it, patch.pixels.first()) > 8 } >= 8,
            "采样前提：真实入口必须有可辨认字形，不能比较空白背景")
    }

    private companion object {
        val QUERIES = listOf("1", "11", "111", "11", "1", "")
    }
}
