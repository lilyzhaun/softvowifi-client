package com.vowifi.sw.manager.ui

import androidx.compose.runtime.snapshots.Snapshot
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B8: the real card's paint must follow its allocated height, not jump to the target size. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationCollapseSurfaceRenderTest {
    @Test fun collapsingCardKeepsItsSurfaceUntilItsAnimatedHeightHasContracted() = onMotionTestThread { runTest {
        ConfigurationStateMotionRenderTest.Fixture(testScheduler, initiallyExpanded = true).use { f ->
            val expandedHeight = f.subjectBounds().height
            val surface = surfaceSample(f)
            f.tapText(ConfigurationStateMotionRenderTest.Fixture.TITLE)
            assertTrue(!f.expanded, "前提：真实头部已经提交收起")
            val heights = mutableListOf<Float>()
            repeat(40) { frame ->
                f.advance(16)
                heights += f.subjectBounds().height
                assertSurfaceFollowsHeight(f, surface, "收起第${frame + 1}帧")
            }
            val collapsedHeight = f.subjectBounds().height
            assertTrue(expandedHeight - collapsedHeight > 80f, "前提：真实卡片确有可收起内容")
            assertTrue(heights.any { it > collapsedHeight + 8f && it < expandedHeight - 8f },
                "收起必须有实际高度中帧，不能直接取消动画")
            assertEquals(0, f.submissions, "收起呈现不能保存配置")
        }
    } }

    @Test fun zeroAnimationScaleContractsImmediatelyWithoutRetainingBlankSpace() = onMotionTestThread { runTest {
        ConfigurationStateMotionRenderTest.Fixture(testScheduler, initiallyExpanded = true, durationScale = 0f).use { f ->
            val expandedHeight = f.subjectBounds().height
            val surface = surfaceSample(f)
            f.tapText(ConfigurationStateMotionRenderTest.Fixture.TITLE)
            val at = testScheduler.currentTime
            // Commit transition disposal and its size target at the same clock instant.
            repeat(6) { Snapshot.sendApplyNotifications(); f.environment.advanceFrameBy(0) }
            assertEquals(at, testScheduler.currentTime, "零动画提交不得靠推进时间等正常动画完成")
            assertTrue(!f.expanded, "前提：真实头部点击确已收起")
            val collapsedHeight = f.subjectBounds().height
            assertTrue(expandedHeight - collapsedHeight > 80f,
                "零动画收起应在同刻帧序列完成：展开=$expandedHeight 当前=$collapsedHeight")
            assertSurfaceFollowsHeight(f, surface, "零动画收起终态")
            f.advance(640)
            assertEquals(collapsedHeight, f.subjectBounds().height, 1f)
            assertEquals(0, f.submissions)
        }
    } }

    @Test fun reversingAContractingCardKeepsTheSamePaintAndDraft() = onMotionTestThread { runTest {
        ConfigurationStateMotionRenderTest.Fixture(testScheduler, initiallyExpanded = true).use { f ->
            f.editSmscDraft("+123")
            f.advance(1000)
            val expandedHeight = f.subjectBounds().height
            val surface = surfaceSample(f)
            f.tapText(ConfigurationStateMotionRenderTest.Fixture.TITLE)
            f.advance(160)
            val contractingHeight = f.subjectBounds().height
            assertTrue(contractingHeight < expandedHeight - 4f,
                "前提：须在真实尺寸收缩中反向，不只测内容淡出")
            assertSurfaceFollowsHeight(f, surface, "反向前收缩中帧")
            f.tapText(ConfigurationStateMotionRenderTest.Fixture.TITLE)
            assertTrue(f.expanded, "反向点按应即时受理")
            repeat(35) { frame ->
                f.advance(16)
                assertSurfaceFollowsHeight(f, surface, "反向展开第${frame + 1}帧")
            }
            assertEquals(expandedHeight, f.subjectBounds().height, 1f)
            assertEquals("+123", f.draft.smsc.text.toString())
            assertTrue(f.draft.dirty)
            assertEquals(0, f.submissions)
        }
    } }

    private fun surfaceSample(f: ConfigurationStateMotionRenderTest.Fixture): Int = f.pixels { bitmap ->
        val bounds = f.subjectBounds()
        // Blank strip outside the trailing icon and above the rounded bottom corner / 12dp gap.
        bitmap.getColor((bounds.right - 8f).roundToInt(), (bounds.bottom - 36f).roundToInt())
    }

    private fun assertSurfaceFollowsHeight(
        f: ConfigurationStateMotionRenderTest.Fixture,
        surface: Int,
        phase: String,
    ) = f.pixels { bitmap ->
        val bounds = f.subjectBounds()
        val x = (bounds.right - 8f).roundToInt()
        val y = (bounds.bottom - 36f).roundToInt()
        assertTrue(x in 1 until bitmap.width - 1 && y in 1 until bitmap.height - 1,
            "采样前提：卡片末端应完整位于画布内")
        val matches = (-1..1).sumOf { dy -> (-1..1).count { dx ->
            val actual = bitmap.getColor(x + dx, y + dy)
            listOf(16, 8, 0).all { shift ->
                kotlin.math.abs((actual ushr shift and 255) - (surface ushr shift and 255)) <= 2
            }
        } }
        assertEquals(9, matches,
            "$phase：卡片仍分配height=${bounds.height}，其内部末端却已变为页面底色，背景不能提前跳到最终尺寸")
    }
}
