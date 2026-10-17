package com.vowifi.sw.manager.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.S3HomeRefreshEntryDraftTest.HomeEntryDriver
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 两个全新真实Home场景使用同一指针/相对帧序列；不修改driver，不模拟库状态。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3RefreshAdmissionTailRenderTest {
    @Test
    fun closingProvisionAdmissionPreservesTheAcceptedHomesNormalExitTrajectory() = onMotionTestThread {
        val normal = capture(closeAdmission = false)
        val closed = capture(closeAdmission = true)
        assertEquals(normal.rest.heroInList, closed.rest.heroInList, "前提：两场景自然主卡布局等价")
        assertEquals(normal.rest.listTop, closed.rest.listTop, "前提：同一Home基线视口等价")
        assertEquals(normal.frames.map { it.at }, closed.frames.map { it.at }, "必须使用完全相同的帧序列")
        for ((expected, actual) in normal.frames.zip(closed.frames)) {
            val normalShift = expected.listTop - normal.rest.listTop
            val closedShift = actual.listTop - closed.rest.listTop
            val context = "t=${expected.at}ms normalOccupation=${expected.occupation} " +
                "closedOccupation=${actual.occupation} normalShift=$normalShift closedShift=$closedShift " +
                "closedOwner=${actual.listOwner} closedPTR=${actual.indicatorOwner}"
            // 对照的是实际布局轨迹，不引入任何允许跳幅或私有RefreshState判定。
            assertEquals(normalShift, closedShift, "关闭新请求准入不能截断已接受的退出轨迹；$context")
            if (expected.occupation != normal.rest.occupation) {
                assertNotNull(actual.occupation, "正常对照仍有实际头部占位，本次退出不能直接卸载；$context")
                assertEquals(expected.occupation, actual.occupation, "真实pullOccupation须与正常退出一致；$context")
                assertEquals(closed.rest.indicatorOwner, actual.indicatorOwner, "退出途中保留原库owner；$context")
            }
        }
    }

    private fun capture(closeAdmission: Boolean): Trace {
        lateinit var result: Trace
        // 独立时钟均从0开始，避免对照场景继承前一次物理动画的绝对帧相位。
        runTest {
            HomeEntryDriver(testScheduler).use { driver ->
                driver.frames(30)
                val rest = sample(driver, -1L)
                assertNotNull(rest.occupation, "前提：原Home真实PTR存在")
                driver.assertAtTop()
                driver.beginDownwardDrag(180f)
                driver.assertRealDownwardInput()
                assertEquals(1f, driver.pullProgress())
                driver.finishDrag()
                for (attempt in 0 until 1800) {
                    if (driver.refreshCalls != 0) break
                    driver.environment.advanceFrameBy(1)
                }
                assertEquals(1, driver.refreshCalls, "前提：真实过阈值松手只受理一次")
                assertEquals(RefreshResult.Completed, driver.completedResult)
                assertTrue(driver.producer?.isCompleted == true, "必须是同帧同步完成，不以计时替代IO")
                val started = requireNotNull(driver.requestStartedAtMillis)
                assertEquals(started, driver.readCompletedAtMillis, "前提：该读取真实同步完成")
                assertEquals(started, testScheduler.currentTime, "前提：从实际受理帧开始记录")
                val frames = mutableListOf<Frame>()
                val offsets = (listOf(0L, 1L, 200L, 201L, 232L, 399L, 400L, 401L, 600L, 601L, 1800L) +
                    (16L..1800L step 16).toList()).distinct().sorted()
                for (offset in offsets) {
                    driver.environment.advanceFrameBy(started + offset - testScheduler.currentTime)
                    if (offset == 200L) {
                        // Fixture没有活动实例：只关闭读取准入，不乐观插入配置/业务卡。
                        if (closeAdmission) driver.provisioning.value = ConfigurationProvisionState(
                            pending = setOf(SimHandle("test-sim")))
                        driver.environment.advanceFrameBy(0) // 对照亦提交同一0ms帧。
                    }
                    if (offset == 232L) {
                        assertEquals(closeAdmission, driver.refreshButton().config.contains(SemanticsProperties.Disabled),
                            "前提：真实Flow已消费，只有关闭场景拒绝新请求")
                        assertEquals(if (closeAdmission) setOf(SimHandle("test-sim")) else emptySet(),
                            driver.provisioning.value.pending)
                    }
                    val frame = sample(driver, offset)
                    assertEquals(rest.listOwner, frame.listOwner, "同一列表owner贯穿400ms/完成段/回收；t=$offset")
                    assertEquals(rest.heroInList, frame.heroInList, "隔离前提：自然主卡相对内容锚点不变；t=$offset")
                    assertEquals(0f, driver.axis().value(), "同一顶端滚动锚点不能被重新定位；t=$offset")
                    assertEquals(1, driver.refreshCalls, "退出过程不能重放读取；t=$offset")
                    assertEquals(RefreshResult.Completed, driver.completedResult)
                    frames += frame
                }
                assertEquals(rest.listTop, frames.last().listTop, "1800ms有界回收后须恢复原视口")
                if (!closeAdmission) {
                    assertEquals(rest.occupation, frames.last().occupation, "对照须完成库收尾及回收")
                    assertEquals(0f, driver.pullProgress(), "对照真实公开进度须自然回零")
                    assertTrue(frames.any { it.at in 400L..800L && it.occupation != rest.occupation },
                        "采样前提：正常对照须实际覆盖最低展示后的退出过程")
                }
                assertEquals(0, driver.otherActions)
                result = Trace(rest, frames)
            }
        }
        return result
    }

    private fun sample(driver: HomeEntryDriver, at: Long): Frame {
        val nodes = driver.nodes()
        val list = nodes.single { it.config.contains(SemanticsActions.ScrollToIndex) }
        val naturalNodes = driver.environment.scene.semanticsOwners.flatMap { owner ->
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            tree(owner.unmergedRootSemanticsNode)
        }
        val titles = naturalNodes.filter { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "暂无活动 SIM" } == true
        }
        assertTrue(titles.size >= 2, "采样前提：原主卡与facts文字都存在，不能任选同文案first")
        val tallest = titles.maxOf { it.boundsInRoot.height }
        val hero = titles.single { it.boundsInRoot.height == tallest }.boundsInRoot
        assertTrue(hero.height > 0f && hero.top >= 0f && hero.bottom <= 800f, "自然主卡标题必须完整可见")
        val indicators = nodes.filter {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("下拉刷新进度") == true
        }
        assertTrue(indicators.size <= 1, "不得用第二个wrapper替代原退出过程")
        val indicator = indicators.singleOrNull()
        val top = driver.listTop()
        // Wrapper已移除时保留真实列表/主卡测量，不抛缺节点异常冒充动画RED。
        return Frame(at, top, if (indicator != null) driver.pullOccupation() else null,
            list.id, indicator?.id, Rect(hero.left, hero.top - top, hero.right, hero.bottom - top))
    }

    private data class Frame(
        val at: Long,
        val listTop: Float,
        val occupation: Float?,
        val listOwner: Int,
        val indicatorOwner: Int?,
        val heroInList: Rect,
    )
    private data class Trace(val rest: Frame, val frames: List<Frame>)
}
